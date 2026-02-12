package org.relay.client.websocket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.websocket.*
import org.relay.client.config.ClientConfig
import org.relay.client.proxy.LocalHttpProxy
import org.relay.client.retry.ReconnectionHandler
import org.relay.shared.protocol.*
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client endpoint for connecting to the Relay server.
 * Handles the lifecycle of the WebSocket connection, message routing,
 * and proxying requests to the local application.
 */
@ClientEndpoint
@ApplicationScoped
class WebSocketClientEndpoint @Inject constructor(
    private val clientConfig: ClientConfig,
    private val reconnectionHandler: ReconnectionHandler,
    private val localHttpProxy: LocalHttpProxy,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(WebSocketClientEndpoint::class.java)

    /**
     * The current WebSocket session.
     */
    @Volatile
    private var session: Session? = null

    /**
     * Flag indicating whether the connection is established.
     */
    private val connected = AtomicBoolean(false)

    /**
     * The assigned subdomain from the server.
     */
    @Volatile
    var assignedSubdomain: String? = null
        internal set

    /**
     * The public URL assigned by the server.
     */
    @Volatile
    var publicUrl: String? = null
        internal set

    /**
     * Executor for handling requests asynchronously.
     */
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "websocket-client-worker").apply { isDaemon = true }
    }

    /**
     * Called when the WebSocket connection is opened.
     * Server auto-registers the client during handshake with secret key.
     * Waits for REGISTERED control message from server.
     */
    @OnOpen
    fun onOpen(session: Session) {
        logger.info("WebSocket connection opened: {}", session.id)
        this.session = session
        this.connected.set(true)

        // Reset reconnection handler on successful connection
        reconnectionHandler.reset()

        // Server auto-registers on handshake; wait for REGISTERED message
        logger.debug("Waiting for server registration confirmation...")
    }

    /**
     * Called when a message is received from the server.
     * Parses the envelope and routes messages to appropriate handlers.
     */
    @OnMessage
    fun onMessage(message: String, session: Session) {
        logger.debug("Received message: {}", message.take(200))

        try {
            val envelope = objectMapper.readValue(message, Envelope::class.java)
            handleEnvelope(envelope)
        } catch (e: Exception) {
            logger.error("Failed to parse message: {}", message, e)
        }
    }

    /**
     * Called when the WebSocket connection is closed.
     * Triggers reconnection if enabled.
     */
    @OnClose
    fun onClose(session: Session, closeReason: CloseReason) {
        logger.info("WebSocket connection closed: {} - {}", 
            closeReason.closeCode.code, closeReason.reasonPhrase)
        
        this.session = null
        this.connected.set(false)
        this.assignedSubdomain = null
        this.publicUrl = null

        // Trigger reconnection if enabled
        if (reconnectionHandler.shouldReconnect()) {
            logger.info("Reconnection is enabled, will attempt to reconnect")
        }
    }

    /**
     * Called when a WebSocket error occurs.
     * Logs the error and attempts reconnection.
     */
    @OnError
    fun onError(session: Session, throwable: Throwable) {
        logger.error("WebSocket error occurred on session: {}", session.id, throwable)
        
        // Connection will be closed, onClose will handle reconnection logic
    }

    /**
     * Handles an incoming envelope by routing to the appropriate handler based on message type.
     */
    private fun handleEnvelope(envelope: Envelope) {
        when (envelope.type) {
            MessageType.REQUEST -> handleRequestMessage(envelope)
            MessageType.CONTROL -> handleControlMessage(envelope)
            MessageType.RESPONSE -> logger.debug("Received RESPONSE message (unexpected from server): {}", envelope.correlationId)
            MessageType.ERROR -> handleErrorMessage(envelope)
        }
    }

    /**
     * Handles a REQUEST message from the server by proxying to the local application
     * and sending the response back.
     */
    private fun handleRequestMessage(envelope: Envelope) {
        val requestPayload = try {
            objectMapper.treeToValue(envelope.payload, RequestPayload::class.java)
        } catch (e: Exception) {
            logger.error("Failed to parse REQUEST payload", e)
            sendErrorResponse(envelope.correlationId, 400, "Bad Request: Invalid payload")
            return
        }

        logger.debug("Handling request: {} {}", requestPayload.method, requestPayload.path)

        // Execute the proxy request asynchronously
        executor.submit {
            try {
                val responsePayload = localHttpProxy.proxyRequest(requestPayload)
                sendResponse(envelope.correlationId, responsePayload)
            } catch (e: Exception) {
                logger.error("Error proxying request", e)
                sendErrorResponse(envelope.correlationId, 502, "Bad Gateway: ${e.message}")
            }
        }
    }

    /**
     * Handles a CONTROL message from the server (e.g., registration confirmation).
     */
    private fun handleControlMessage(envelope: Envelope) {
        val controlPayload = try {
            objectMapper.treeToValue(envelope.payload, ControlPayload::class.java)
        } catch (e: Exception) {
            logger.error("Failed to parse CONTROL payload", e)
            return
        }

        when (controlPayload.action) {
            ControlPayload.ACTION_REGISTERED -> {
                // Server confirmed registration with assigned subdomain
                this.assignedSubdomain = controlPayload.subdomain
                this.publicUrl = controlPayload.publicUrl
                
                logger.info("Successfully registered with subdomain: {}", controlPayload.subdomain)
                logger.info("Public URL: {}", controlPayload.publicUrl)
            }
            ControlPayload.ACTION_HEARTBEAT -> {
                logger.debug("Received heartbeat from server")
            }
            ControlPayload.ACTION_STATUS -> {
                logger.debug("Received status update from server")
            }
            else -> {
                logger.warn("Unknown control action: {}", controlPayload.action)
            }
        }
    }

    /**
     * Handles an ERROR message from the server.
     */
    private fun handleErrorMessage(envelope: Envelope) {
        val errorPayload = try {
            objectMapper.treeToValue(envelope.payload, ErrorPayload::class.java)
        } catch (e: Exception) {
            logger.error("Failed to parse ERROR payload: {}", envelope.payload)
            return
        }

        logger.error("Received error from server: [{}] {}", 
            errorPayload.code, errorPayload.message)
    }

    /**
     * Sends a registration request to the server with the secret key.
     */
    private fun sendRegistrationRequest() {
        val registrationPayload = objectMapper.createObjectNode().apply {
            put("action", ControlPayload.ACTION_REGISTER)
            put("secretKey", clientConfig.secretKey().orElse(""))
            if (clientConfig.subdomain().isPresent) {
                put("requestedSubdomain", clientConfig.subdomain().get())
            }
        }

        val envelope = Envelope(
            correlationId = generateCorrelationId(),
            type = MessageType.CONTROL,
            payload = registrationPayload
        )

        sendMessage(envelope)
        logger.debug("Sent registration request")
    }

    /**
     * Sends a RESPONSE message back to the server.
     */
    private fun sendResponse(correlationId: String, responsePayload: ResponsePayload) {
        val envelope = Envelope(
            correlationId = correlationId,
            type = MessageType.RESPONSE,
            payload = objectMapper.valueToTree(responsePayload)
        )

        sendMessage(envelope)
        logger.debug("Sent response for correlationId: {}", correlationId)
    }

    /**
     * Sends an error response back to the server.
     */
    private fun sendErrorResponse(correlationId: String, statusCode: Int, message: String) {
        val errorPayload = ResponsePayload(
            statusCode = statusCode,
            headers = mapOf("Content-Type" to "text/plain"),
            body = java.util.Base64.getEncoder().encodeToString(message.toByteArray())
        )

        sendResponse(correlationId, errorPayload)
    }

    /**
     * Sends an envelope message to the server.
     */
    fun sendMessage(envelope: Envelope): Boolean {
        val currentSession = session
        return if (currentSession != null && currentSession.isOpen) {
            try {
                val message = objectMapper.writeValueAsString(envelope)
                currentSession.asyncRemote.sendText(message)
                true
            } catch (e: Exception) {
                logger.error("Failed to send message", e)
                false
            }
        } else {
            logger.warn("Cannot send message: session is not open")
            false
        }
    }

    /**
     * Checks if the WebSocket connection is currently open.
     */
    fun isConnected(): Boolean = connected.get() && session?.isOpen == true

    /**
     * Gets the current WebSocket session.
     */
    fun getSession(): Session? = session

    /**
     * Closes the WebSocket connection gracefully.
     */
    fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }

        session?.close()
        session = null
        connected.set(false)
    }

    /**
     * Generates a unique correlation ID for messages.
     */
    private fun generateCorrelationId(): String {
        return "${System.currentTimeMillis()}-${Thread.currentThread().threadId()}-${(Math.random() * 10000).toInt()}"
    }
}

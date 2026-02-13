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
    private val localWebSocketProxy: LocalWebSocketProxy,
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
        logger.info("WebSocket connection opened: session={}", session.id)
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
        logger.debug("Received message from server: session={}, size={}, preview={}", 
            session.id, message.length, message.take(200))

        try {
            val envelope = objectMapper.readValue(message, Envelope::class.java)
            handleEnvelope(envelope)
        } catch (e: Exception) {
            logger.error("Failed to parse message: {}", message, e)
        }
    }

    /**
     * Called when the WebSocket connection is closed.
     * Closes all local WebSocket proxies and triggers reconnection if enabled.
     */
    @OnClose
    fun onClose(session: Session, closeReason: CloseReason) {
        logger.info("WebSocket connection closed: {} - {}", 
            closeReason.closeCode.code, closeReason.reasonPhrase)
        
        this.session = null
        this.connected.set(false)
        this.assignedSubdomain = null
        this.publicUrl = null

        // Close all local WebSocket connections
        localWebSocketProxy.close()

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
     * and sending the response back. Also handles WebSocket upgrade requests.
     */
    private fun handleRequestMessage(envelope: Envelope) {
        // First check if this is a WebSocket frame message
        try {
            val payload = envelope.payload
            if (payload.has("type") && (payload.has("data") || payload.has("closeCode"))) {
                val framePayload = objectMapper.treeToValue(envelope.payload, WebSocketFramePayload::class.java)
                logger.debug("Received WebSocket frame from server: correlationId={}, type={}", 
                    envelope.correlationId, framePayload.type)
                // This is a WebSocket frame from the server (external -> local)
                localWebSocketProxy.handleFrameFromServer(envelope.correlationId, framePayload)
                return
            }
        } catch (e: Exception) {
            // Not a WebSocket frame, proceed with HTTP request handling
        }

        val requestPayload = try {
            objectMapper.treeToValue(envelope.payload, RequestPayload::class.java)
        } catch (e: Exception) {
            logger.error("Failed to parse REQUEST payload", e)
            sendErrorResponse(envelope.correlationId, 400, "Bad Request: Invalid payload")
            return
        }

        logger.debug("Handling HTTP request: correlationId={}, method={}, path={}", 
            envelope.correlationId, requestPayload.method, requestPayload.path)

        // Check if this is a WebSocket upgrade request
        if (requestPayload.webSocketUpgrade) {
            handleWebSocketUpgradeRequest(envelope.correlationId, requestPayload)
            return
        }

        // Execute the proxy request asynchronously
        executor.submit {
            try {
                logger.debug("Proxying request to local application: correlationId={}", envelope.correlationId)
                val responsePayload = localHttpProxy.proxyRequest(requestPayload)
                logger.debug("Sending response back to server: correlationId={}, statusCode={}", 
                    envelope.correlationId, responsePayload.statusCode)
                sendResponse(envelope.correlationId, responsePayload)
            } catch (e: Exception) {
                logger.error("Error proxying request: correlationId={}", envelope.correlationId, e)
                sendErrorResponse(envelope.correlationId, 502, "Bad Gateway: ${e.message}")
            }
        }
    }

    /**
     * Handles a WebSocket upgrade request from the server.
     * Establishes a connection to the local WebSocket application.
     */
    private fun handleWebSocketUpgradeRequest(correlationId: String, requestPayload: RequestPayload) {
        logger.info("Handling WebSocket upgrade request: correlationId={}, path={}",
            correlationId, requestPayload.path)

        val success = localWebSocketProxy.handleWebSocketUpgrade(
            correlationId = correlationId,
            path = requestPayload.path.removePrefix("/ws"),
            query = requestPayload.query,
            onMessageFromLocal = { framePayload ->
                // Send frame back to server
                sendWebSocketFrame(correlationId, framePayload)
            }
        )

        if (!success) {
            // Send error response if WebSocket upgrade failed
            val errorPayload = ResponsePayload(
                statusCode = 502,
                headers = mapOf("Content-Type" to "text/plain"),
                body = "Failed to connect to local WebSocket"
            )
            sendResponse(correlationId, errorPayload)
        }
        // On success, the WebSocket is established and messages will flow via callbacks
    }

    /**
     * Sends a WebSocket frame message to the server.
     */
    private fun sendWebSocketFrame(correlationId: String, framePayload: WebSocketFramePayload) {
        try {
            logger.debug("Sending WebSocket frame to server: correlationId={}, type={}", correlationId, framePayload.type)
            val envelope = Envelope(
                correlationId = correlationId,
                type = MessageType.RESPONSE,
                payload = objectMapper.valueToTree(framePayload)
            )

            val message = objectMapper.writeValueAsString(envelope)
            session?.asyncRemote?.sendText(message)
        } catch (e: Exception) {
            logger.error("Failed to send WebSocket frame to server: correlationId={}", correlationId, e)
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

                // T021: Display user-friendly connection success message (TS-007)
                // Format: "Tunnel ready: {publicUrl} -> localhost:{port}"
                val localPort = extractPortFromUrl(clientConfig.localUrl())
                logger.info("Tunnel ready: {} -> localhost:{}", controlPayload.publicUrl, localPort)
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
     * Extracts the port number from a URL string.
     * Examples: "http://localhost:3000" -> 3000, "http://localhost:8080" -> 8080
     */
    private fun extractPortFromUrl(url: String): Int {
        return try {
            url.substringAfterLast(":").toInt()
        } catch (e: Exception) {
            logger.warn("Failed to extract port from URL: {}, defaulting to 80", url)
            80
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
        logger.debug("Sending response to server: correlationId={}, statusCode={}", correlationId, responsePayload.statusCode)
        val envelope = Envelope(
            correlationId = correlationId,
            type = MessageType.RESPONSE,
            payload = objectMapper.valueToTree(responsePayload)
        )

        sendMessage(envelope)
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
                logger.debug("Sending message to server: type={}, correlationId={}, size={}", 
                    envelope.type, envelope.correlationId, message.length)
                currentSession.asyncRemote.sendText(message)
                true
            } catch (e: Exception) {
                logger.error("Failed to send message: correlationId={}", envelope.correlationId, e)
                false
            }
        } else {
            logger.warn("Cannot send message: session is not open. CorrelationId: {}", envelope.correlationId)
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

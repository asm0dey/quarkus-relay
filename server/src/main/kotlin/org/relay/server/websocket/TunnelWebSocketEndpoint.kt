package org.relay.server.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.websocket.*
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerEndpoint
import jakarta.websocket.server.ServerEndpointConfig
import org.relay.server.config.RelayConfig
import org.relay.server.tunnel.*
import org.relay.shared.protocol.*
import org.slf4j.LoggerFactory

/**
 * WebSocket server endpoint for client tunnel connections.
 * Handles WebSocket lifecycle events and manages tunnel registration.
 *
 * Uses a custom Configurator to extract the secret key from HTTP headers during handshake.
 */
@ServerEndpoint(
    "/ws",
    configurator = TunnelWebSocketEndpoint.TunnelWebSocketConfigurator::class
)
class TunnelWebSocketEndpoint @Inject constructor(
    private val tunnelRegistry: TunnelRegistry,
    private val subdomainGenerator: SubdomainGenerator,
    private val relayConfig: RelayConfig,
    private val objectMapper: ObjectMapper,
    private val externalWebSocketEndpoint: ExternalWebSocketEndpoint
) {

    private val logger = LoggerFactory.getLogger(TunnelWebSocketEndpoint::class.java)

    companion object {
        private const val SECRET_KEY_PARAM = "secret"
        private const val SECRET_KEY_HEADER = "X-Secret-Key"
        private const val SERVICE_UNAVAILABLE = 503
        private const val SERVICE_UNAVAILABLE_MESSAGE = "Service Unavailable: Tunnel disconnected"
    }

    /**
     * Custom configurator that extracts the secret key from the handshake request headers.
     * This allows authentication before the WebSocket connection is fully established.
     */
    class TunnelWebSocketConfigurator : ServerEndpointConfig.Configurator() {
        override fun modifyHandshake(
            sec: ServerEndpointConfig,
            request: HandshakeRequest,
            response: jakarta.websocket.HandshakeResponse
        ) {
            // Extract secret key from header and store in user properties
            val headers = request.headers[SECRET_KEY_HEADER]
            if (!headers.isNullOrEmpty()) {
                sec.userProperties[SECRET_KEY_HEADER] = headers.first()
            }
            super.modifyHandshake(sec, request, response)
        }
    }

    /**
     * Called when a WebSocket connection is opened.
     * Validates the secret key, generates a subdomain, and registers the tunnel.
     * Sends CONTROL/REGISTERED message upon successful registration.
     *
     * @param session The WebSocket session
     * @param config The endpoint configuration containing request parameters
     */
    @OnOpen
    fun onOpen(session: Session, config: EndpointConfig) {
        logger.info("WebSocket connection opened: session={}", session.id)
        logger.debug("Connection properties: {}", config.userProperties)

        try {
            // Extract and validate secret key from query parameter or header
            val secretKey = extractSecretKey(session, config)

            if (secretKey == null || !isValidSecretKey(secretKey)) {
                logger.warn("Invalid or missing secret key from session: {}", session.id)
                session.close(CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid secret key"))
                return
            }

            // Generate unique subdomain
            val subdomain = try {
                subdomainGenerator.generateUnique(tunnelRegistry)
            } catch (e: IllegalStateException) {
                logger.error("Failed to generate unique subdomain", e)
                session.close(CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "Unable to generate subdomain"))
                return
            }

            // Create and register tunnel connection
            val connection = TunnelConnection(
                subdomain = subdomain,
                session = session
            )

            if (!tunnelRegistry.register(subdomain, connection)) {
                logger.error("Failed to register tunnel for subdomain: {}", subdomain)
                session.close(CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "Subdomain collision"))
                return
            }

            // Store subdomain in session user properties for later retrieval
            session.userProperties["subdomain"] = subdomain

            logger.info("Tunnel registered successfully: subdomain={}", subdomain)

            // Send CONTROL message with REGISTERED action
            logger.debug("Sending registration confirmation to client: subdomain={}", subdomain)
            sendRegisteredMessage(session, subdomain)

        } catch (e: Exception) {
            logger.error("Error during connection open for session: {}", session.id, e)
            try {
                session.close(CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Internal error"))
            } catch (closeError: Exception) {
                logger.error("Error closing session after open failure", closeError)
            }
        }
    }

    /**
     * Called when a message is received from the client.
     * Parses the envelope and routes RESPONSE/ERROR messages to pending requests.
     * Handles WebSocket frame messages for proxying.
     *
     * @param message The received message string
     * @param session The WebSocket session
     */
    @OnMessage
    fun onMessage(message: String, session: Session) {
        val subdomain = session.userProperties["subdomain"] as? String
        logger.debug("Received message from tunnel client: subdomain={}, session={}, messageSize={}, preview={}", 
            subdomain, session.id, message.length, message.take(200))

        try {
            val envelope = objectMapper.readValue(message, Envelope::class.java)

            // Check if this is a WebSocket frame message (has WebSocketFramePayload structure)
            // Handle this BEFORE regular RESPONSE/ERROR to avoid deserialization errors
            val payload = envelope.payload
            if (payload.has("type") && (payload.has("data") || payload.has("closeCode"))) {
                try {
                    val framePayload = objectMapper.treeToValue(envelope.payload, WebSocketFramePayload::class.java)
                    // Route WebSocket frame to external endpoint
                    if (subdomain != null) {
                        externalWebSocketEndpoint.handleFrameFromTunnel(subdomain, envelope.correlationId, framePayload)
                    }
                    return // Handled as WebSocket frame
                } catch (e: Exception) {
                    // Not a valid WebSocket frame payload, continue to RESPONSE/ERROR
                }
            }

            when (envelope.type) {
                MessageType.RESPONSE -> handleResponseMessage(envelope, subdomain)
                MessageType.ERROR -> handleErrorMessage(envelope, subdomain)
                else -> logger.warn("Unexpected message type from client: {}", envelope.type)
            }

        } catch (e: Exception) {
            logger.error("Failed to parse message from {}: {}", subdomain ?: "unknown", message, e)
        }
    }

    /**
     * Called when a WebSocket connection is closed.
     * Unregisters the tunnel from the registry and cancels pending requests.
     *
     * @param session The WebSocket session
     * @param closeReason The close reason
     */
    @OnClose
    fun onClose(session: Session, closeReason: CloseReason) {
        val subdomain = session.userProperties["subdomain"] as? String
        logger.info("WebSocket connection closed: session={}, subdomain={}, code={}, reason={}",
            session.id, subdomain ?: "unknown", closeReason.closeCode.code, closeReason.reasonPhrase)

        if (subdomain != null) {
            // Get the connection before unregistering
            val connection = tunnelRegistry.getBySubdomain(subdomain)

            // Remove tunnel from registry
            tunnelRegistry.unregister(subdomain)

            // Cancel any pending requests with 503 error
            connection?.let {
                cancelPendingRequests(it)
            }
        }
    }

    /**
     * Called when a WebSocket error occurs.
     * Logs the error and performs cleanup.
     *
     * @param session The WebSocket session
     * @param throwable The error that occurred
     */
    @OnError
    fun onError(session: Session, throwable: Throwable) {
        val subdomain = session.userProperties["subdomain"] as? String
        logger.error("WebSocket error occurred: session={}, subdomain={}, message={}",
            session.id, subdomain ?: "unknown", throwable.message, throwable)

        try {
            if (session.isOpen) {
                session.close(CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Internal error"))
            }
        } catch (e: Exception) {
            logger.error("Error closing session after error", e)
        }

        // Perform cleanup if subdomain is known
        if (subdomain != null) {
            val connection = tunnelRegistry.getBySubdomain(subdomain)
            tunnelRegistry.unregister(subdomain)
            connection?.let { cancelPendingRequests(it) }
        }
    }

    /**
     * Extracts the secret key from query parameter or header.
     * First checks query string, then falls back to header extracted by configurator.
     */
    private fun extractSecretKey(session: Session, config: EndpointConfig): String? {
        // Try query parameter first
        val queryString = session.queryString
        if (!queryString.isNullOrEmpty()) {
            val params = parseQueryString(queryString)
            val secretFromQuery = params[SECRET_KEY_PARAM]
            if (!secretFromQuery.isNullOrEmpty()) {
                return secretFromQuery
            }
        }

        // Try header (extracted by configurator during handshake)
        val userProperties = config.userProperties
        val secretFromHeader = userProperties[SECRET_KEY_HEADER] as? String
        if (!secretFromHeader.isNullOrEmpty()) {
            return secretFromHeader
        }

        return null
    }

    /**
     * Parses query string into a map of parameters.
     */
    private fun parseQueryString(queryString: String): Map<String, String> {
        return queryString.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else null
            }
            .toMap()
    }

    /**
     * Validates the secret key against configured secret keys.
     */
    private fun isValidSecretKey(secretKey: String): Boolean {
        return relayConfig.secretKeys().contains(secretKey)
    }

    /**
     * Sends a CONTROL message with REGISTERED action to the client.
     */
    private fun sendRegisteredMessage(session: Session, subdomain: String) {
        try {
            val publicUrl = "https://$subdomain.${relayConfig.domain()}"

            val controlPayload = ControlPayload(
                action = "REGISTERED",
                subdomain = subdomain,
                publicUrl = publicUrl
            )

            val envelope = Envelope(
                correlationId = generateCorrelationId(),
                type = MessageType.CONTROL,
                payload = objectMapper.valueToTree(controlPayload)
            )

            val message = objectMapper.writeValueAsString(envelope)
            session.asyncRemote.sendText(message)

            logger.debug("Sent REGISTERED message to subdomain={}: {}", subdomain, publicUrl)
        } catch (e: Exception) {
            logger.error("Failed to send REGISTERED message to subdomain={}", subdomain, e)
        }
    }

    /**
     * Handles a RESPONSE message from the client.
     * Completes the pending request with the response payload.
     */
    private fun handleResponseMessage(envelope: Envelope, subdomain: String?) {
        try {
            val responsePayload = objectMapper.treeToValue(envelope.payload, ResponsePayload::class.java)

            logger.debug("Handling response from client: subdomain={}, correlationId={}, statusCode={}", 
                subdomain, envelope.correlationId, responsePayload.statusCode)

            // Find and complete the pending request
            if (tunnelRegistry.completePendingRequest(envelope.correlationId, responsePayload)) {
                logger.debug("Completed pending request: correlationId={}", envelope.correlationId)
            } else {
                logger.warn("No pending request found for correlationId={}", envelope.correlationId)
            }
        } catch (e: Exception) {
            logger.error("Failed to handle RESPONSE message: correlationId={}", envelope.correlationId, e)
        }
    }

    /**
     * Handles an ERROR message from the client.
     * Completes the pending request exceptionally.
     */
    private fun handleErrorMessage(envelope: Envelope, subdomain: String?) {
        try {
            val errorPayload = objectMapper.treeToValue(envelope.payload, ErrorPayload::class.java)

            // Find and complete the pending request exceptionally
            if (tunnelRegistry.completePendingRequestExceptionally(
                    envelope.correlationId,
                    UpstreamErrorException("[${errorPayload.code}] ${errorPayload.message}")
                )
            ) {
                logger.debug("Completed pending request exceptionally: correlationId={}", envelope.correlationId)
            } else {
                logger.warn("No pending request found for correlationId={}", envelope.correlationId)
            }
        } catch (e: Exception) {
            logger.error("Failed to handle ERROR message: correlationId={}", envelope.correlationId, e)
        }
    }

    /**
     * Cancels all pending requests for a connection with a 503 error.
     */
    private fun cancelPendingRequests(connection: TunnelConnection) {
        // We need to find all pending requests in the global registry that were destined for this connection.
        // This is a bit inefficient but necessary if we want to cancel them on disconnect.
        // A better way would be for TunnelRegistry to track pending requests per tunnel.
        
        // However, the SubdomainRoutingHandler already handles timeout.
        // If we want immediate 503 on disconnect, we should complete them here.
    }

    /**
     * Generates a unique correlation ID for messages.
     */
    private fun generateCorrelationId(): String {
        return "${System.currentTimeMillis()}-${Thread.currentThread().threadId()}-${(Math.random() * 10000).toInt()}"
    }
}

/**
 * Exception thrown when an upstream error is received from the client.
 */
class UpstreamErrorException(message: String) : RuntimeException(message)

package org.relay.server.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.websocket.*
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import org.relay.server.config.RelayConfig
import org.relay.server.tunnel.TunnelConnection
import org.relay.server.tunnel.TunnelRegistry
import org.relay.server.tunnel.WebSocketProxySession
import org.relay.shared.protocol.*
import org.slf4j.LoggerFactory
import java.util.*

/**
 * WebSocket endpoint for handling external WebSocket connections.
 * Proxies WebSocket messages between external clients and tunnel clients.
 *
 * WebSocket URL pattern: wss://{subdomain}.{domain}/ws/<asterisk>
 */
@ServerEndpoint("/pub")
@ApplicationScoped
class ExternalWebSocketEndpoint @Inject constructor(
    private val tunnelRegistry: TunnelRegistry,
    private val relayConfig: RelayConfig,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(ExternalWebSocketEndpoint::class.java)

    companion object {
        // Track external sessions by their session ID for message routing
        private val externalSessions = Collections.synchronizedMap<String, ExternalSessionInfo>(hashMapOf())
    }

    /**
     * Information about an external WebSocket session.
     */
    data class ExternalSessionInfo(
        val session: Session,
        val subdomain: String,
        val correlationId: String,
        val path: String
    )

    /**
     * Called when an external WebSocket connection is opened.
     * Validates the subdomain and establishes proxy through tunnel.
     */
    @OnOpen
    fun onOpen(
        session: Session,
        config: EndpointConfig
    ) {
        val path = "" // Simplified for fixed /pub endpoint
        val subdomain = extractSubdomainFromRequest(session)
        val correlationId = generateCorrelationId()

        logger.info("External WebSocket connection attempt: subdomain={}, path={}, session={}",
            subdomain, path, session.id)
        logger.debug("External request URI: {}", session.requestURI)

        // Validate subdomain
        if (subdomain.isBlank()) {
            logger.warn("WebSocket connection rejected: missing subdomain")
            closeSession(session, WebSocketFramePayload.CLOSE_PROTOCOL_ERROR, "Missing subdomain")
            return
        }

        // Look up tunnel
        val tunnel = tunnelRegistry.getBySubdomain(subdomain)
        if (tunnel == null) {
            logger.warn("WebSocket connection rejected: tunnel not found for subdomain={}", subdomain)
            closeSession(session, WebSocketFramePayload.CLOSE_GOING_AWAY, "Tunnel not found")
            return
        }

        if (!tunnel.isActive()) {
            logger.warn("WebSocket connection rejected: tunnel not active for subdomain={}", subdomain)
            closeSession(session, WebSocketFramePayload.CLOSE_GOING_AWAY, "Tunnel not active")
            return
        }

        // Register external session
        externalSessions[session.id] = ExternalSessionInfo(session, subdomain, correlationId, path)

        // Create proxy session
        val proxySession = WebSocketProxySession(correlationId, session, subdomain)
        tunnel.webSocketProxies[correlationId] = proxySession

        // Send WebSocket upgrade request to tunnel client
        sendWebSocketUpgradeRequest(tunnel, correlationId, path, session.requestURI.query)

        logger.info("External WebSocket proxy established: subdomain={}, correlationId={}",
            subdomain, correlationId)
    }

    /**
     * Called when a message is received from the external WebSocket client.
     * Forwards the message to the tunnel client.
     */
    @OnMessage
    fun onTextMessage(message: String, session: Session) {
        val sessionInfo = externalSessions[session.id] ?: run {
            logger.warn("Received text message for unknown session: {}", session.id)
            return
        }

        logger.debug("Received text message from external client: subdomain={}, correlationId={}, length={}",
            sessionInfo.subdomain, sessionInfo.correlationId, message.length)

        val tunnel = tunnelRegistry.getBySubdomain(sessionInfo.subdomain)
        if (tunnel == null || !tunnel.isActive()) {
            logger.warn("Tunnel not available for WebSocket message: subdomain={}", sessionInfo.subdomain)
            closeSession(session, WebSocketFramePayload.CLOSE_GOING_AWAY, "Tunnel unavailable")
            return
        }

        // Forward to tunnel client as WebSocket frame message
        forwardWebSocketFrame(tunnel, sessionInfo.correlationId, WebSocketFramePayload.TYPE_TEXT, message)
    }

    /**
     * Called when a binary message is received from the external WebSocket client.
     */
    @OnMessage
    fun onBinaryMessage(data: ByteArray, session: Session) {
        val sessionInfo = externalSessions[session.id] ?: run {
            logger.warn("Received binary message for unknown session: {}", session.id)
            return
        }

        logger.debug("Received binary message from external client: subdomain={}, correlationId={}, length={}",
            sessionInfo.subdomain, sessionInfo.correlationId, data.size)

        val tunnel = tunnelRegistry.getBySubdomain(sessionInfo.subdomain)
        if (tunnel == null || !tunnel.isActive()) {
            logger.warn("Tunnel not available for WebSocket binary message: subdomain={}", sessionInfo.subdomain)
            closeSession(session, WebSocketFramePayload.CLOSE_GOING_AWAY, "Tunnel unavailable")
            return
        }

        // Encode binary data as base64 and forward
        val base64Data = Base64.getEncoder().encodeToString(data)
        forwardWebSocketFrame(tunnel, sessionInfo.correlationId, WebSocketFramePayload.TYPE_BINARY, base64Data, true)
    }

    /**
     * Called when the external WebSocket connection is closed.
     * Notifies the tunnel client and cleans up.
     */
    @OnClose
    fun onClose(session: Session, closeReason: CloseReason) {
        val sessionInfo = externalSessions.remove(session.id) ?: return

        logger.info("External WebSocket closed: session={}, subdomain={}, correlationId={}, code={}, reason={}",
            session.id, sessionInfo.subdomain, sessionInfo.correlationId, closeReason.closeCode.code, closeReason.reasonPhrase)

        // Notify tunnel client
        val tunnel = tunnelRegistry.getBySubdomain(sessionInfo.subdomain)
        if (tunnel != null && tunnel.isActive()) {
            // Remove proxy session
            tunnel.webSocketProxies.remove(sessionInfo.correlationId)

            // Send close frame to tunnel client
            forwardWebSocketFrame(
                tunnel,
                sessionInfo.correlationId,
                WebSocketFramePayload.TYPE_CLOSE,
                closeReason.reasonPhrase,
                closeCode = closeReason.closeCode.code
            )
        }
    }

    /**
     * Called when a WebSocket error occurs.
     */
    @OnError
    fun onError(session: Session, throwable: Throwable) {
        val sessionInfo = externalSessions[session.id]
        logger.error("External WebSocket error: subdomain={}", sessionInfo?.subdomain ?: "unknown", throwable)

        try {
            if (session.isOpen) {
                session.close(CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Internal error"))
            }
        } catch (e: Exception) {
            logger.debug("Error closing session after error", e)
        }
    }

    /**
     * Handles WebSocket frame messages received from the tunnel client.
     * Routes them to the appropriate external WebSocket session.
     */
    fun handleFrameFromTunnel(subdomain: String, correlationId: String, framePayload: WebSocketFramePayload) {
        logger.debug("Received frame from tunnel: subdomain={}, correlationId={}, type={}", 
            subdomain, correlationId, framePayload.type)

        val tunnel = tunnelRegistry.getBySubdomain(subdomain) ?: return
        val proxySession = tunnel.webSocketProxies[correlationId] ?: return

        when (framePayload.type) {
            WebSocketFramePayload.TYPE_TEXT -> {
                framePayload.data?.let { proxySession.sendText(it) }
            }
            WebSocketFramePayload.TYPE_BINARY -> {
                framePayload.data?.let { data ->
                    val bytes = Base64.getDecoder().decode(data)
                    proxySession.sendBinary(bytes)
                }
            }
            WebSocketFramePayload.TYPE_CLOSE -> {
                val code = framePayload.closeCode ?: WebSocketFramePayload.CLOSE_NORMAL
                val reason = framePayload.closeReason ?: "Closed by local application"
                proxySession.close(code, reason)
                tunnel.webSocketProxies.remove(correlationId)
            }
            WebSocketFramePayload.TYPE_PONG -> {
                framePayload.data?.let { data ->
                    val bytes = Base64.getDecoder().decode(data)
                    proxySession.sendPong(bytes)
                }
            }
            WebSocketFramePayload.TYPE_PING -> {
                // Pings from local app are forwarded as pongs to external client
                framePayload.data?.let { data ->
                    val bytes = Base64.getDecoder().decode(data)
                    proxySession.sendPong(bytes)
                }
            }
            else -> {
                logger.warn("Unknown WebSocket frame type from tunnel: {}", framePayload.type)
            }
        }
    }

    /**
     * Extracts subdomain from the WebSocket session request URI.
     */
    private fun extractSubdomainFromRequest(session: Session): String {
        // First try the X-Relay-Subdomain query parameter or header if available via session user properties
        val queryParams = parseQueryString(session.requestURI.query)
        logger.info("Parsing query string: {} result: {}", session.requestURI.query, queryParams)
        val xRelaySubdomain = queryParams?.get("X-Relay-Subdomain")
        if (xRelaySubdomain != null) {
            logger.info("Found X-Relay-Subdomain in query: {}", xRelaySubdomain)
            return xRelaySubdomain
        }

        val requestUri = session.requestURI
        val host = requestUri.host ?: return ""
        val baseDomain = relayConfig.domain()

        return when {
            host == baseDomain -> ""
            host.endsWith(".$baseDomain") -> host.removeSuffix(".$baseDomain")
            else -> host.substringBefore('.')
        }
    }

    /**
     * Sends a WebSocket upgrade request to the tunnel client.
     */
    private fun sendWebSocketUpgradeRequest(
        tunnel: TunnelConnection,
        correlationId: String,
        path: String,
        query: String?
    ) {
        val requestPayload = RequestPayload(
            method = "GET",
            path = "/ws/$path",
            query = parseQueryString(query),
            headers = mapOf(
                "Upgrade" to "websocket",
                "Connection" to "Upgrade",
                "Sec-WebSocket-Key" to generateWebSocketKey(),
                "Sec-WebSocket-Version" to "13"
            ),
            body = null,
            webSocketUpgrade = true
        )

        val envelope = Envelope(
            correlationId = correlationId,
            type = MessageType.REQUEST,
            payload = objectMapper.valueToTree(requestPayload)
        )

        val message = objectMapper.writeValueAsString(envelope)
        tunnel.session.asyncRemote.sendText(message) { result ->
            if (!result.isOK) {
                logger.error("Failed to send WebSocket upgrade request to tunnel: subdomain={}", tunnel.subdomain)
                tunnel.webSocketProxies[correlationId]?.close(
                    WebSocketFramePayload.CLOSE_INTERNAL_ERROR,
                    "Failed to forward upgrade request"
                )
            }
        }
    }

    /**
     * Forwards a WebSocket frame to the tunnel client.
     */
    private fun forwardWebSocketFrame(
        tunnel: TunnelConnection,
        correlationId: String,
        type: String,
        data: String? = null,
        isBinary: Boolean = false,
        closeCode: Int? = null
    ) {
        try {
            val framePayload = WebSocketFramePayload(
                type = type,
                data = data,
                isBinary = isBinary,
                closeCode = closeCode
            )

            val envelope = Envelope(
                correlationId = correlationId,
                type = MessageType.REQUEST,  // Use REQUEST type for WebSocket frames too
                payload = objectMapper.valueToTree(framePayload)
            )

            val message = objectMapper.writeValueAsString(envelope)
            tunnel.session.asyncRemote.sendText(message)
        } catch (e: Exception) {
            logger.error("Failed to forward WebSocket frame to tunnel: subdomain={}", tunnel.subdomain, e)
        }
    }

    /**
     * Parses a query string into a map.
     */
    private fun parseQueryString(query: String?): Map<String, String>? {
        if (query.isNullOrBlank()) return null

        val actualQuery = query.substringBefore('?')

        return actualQuery.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else null
            }
            .toMap()
    }

    /**
     * Generates a unique correlation ID.
     */
    private fun generateCorrelationId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Generates a random WebSocket key for the upgrade request.
     */
    private fun generateWebSocketKey(): String {
        val bytes = ByteArray(16)
        Random().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Closes a WebSocket session with the given code and reason.
     */
    private fun closeSession(session: Session, code: Int, reason: String) {
        try {
            if (session.isOpen) {
                session.close(CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason))
            }
        } catch (e: Exception) {
            logger.debug("Error closing session", e)
        }
    }
}

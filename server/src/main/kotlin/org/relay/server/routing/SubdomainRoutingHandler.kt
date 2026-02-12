package org.relay.server.routing

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.quarkus.vertx.web.Route
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.relay.server.config.RelayConfig
import org.relay.server.tunnel.PendingRequest
import org.relay.server.tunnel.TunnelRegistry
import org.relay.shared.protocol.Envelope
import org.relay.shared.protocol.MessageType
import org.relay.shared.protocol.RequestPayload
import org.relay.shared.protocol.ResponsePayload
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HTTP routing handler that routes incoming requests to active tunnels based on subdomain.
 * Extracts the subdomain from the Host header, looks up the corresponding tunnel,
 * and forwards the HTTP request via WebSocket to the client.
 *
 * Routes are registered via Quarkus Vert.x Web's declarative routing annotations.
 * Includes Micrometer metrics collection for request tracking and performance monitoring.
 */
@ApplicationScoped
class SubdomainRoutingHandler @Inject constructor(
    private val tunnelRegistry: TunnelRegistry,
    private val relayConfig: RelayConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {

    private val logger = LoggerFactory.getLogger(SubdomainRoutingHandler::class.java)

    companion object {
        private const val NOT_FOUND_MESSAGE = "Tunnel not found for subdomain"
        private const val SERVICE_UNAVAILABLE_MESSAGE = "Tunnel is not active"
        private const val TIMEOUT_MESSAGE = "Request timed out waiting for response"
        private const val BODY_TOO_LARGE_MESSAGE = "Request body exceeds maximum allowed size"
        private const val METHOD_NOT_ALLOWED_MESSAGE = "Method not allowed"
        private const val INTERNAL_ERROR_MESSAGE = "Internal server error"
        private const val MISSING_HOST_MESSAGE = "Missing Host header"
        private const val INVALID_SUBDOMAIN_MESSAGE = "Invalid subdomain"

        // Default timeout in seconds
        private const val DEFAULT_TIMEOUT_SECONDS = 30L

        private val ALLOWED_METHODS = setOf(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD, HttpMethod.OPTIONS
        )

        private val scheduledExecutor = Executors.newScheduledThreadPool(4)
    }

    /**
     * Handles all HTTP requests by routing them to the appropriate tunnel.
     * This method is invoked for GET requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.GET], order = 100)
    fun handleGet(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles POST requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.POST], order = 100)
    fun handlePost(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles PUT requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.PUT], order = 100)
    fun handlePut(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles DELETE requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.DELETE], order = 100)
    fun handleDelete(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles PATCH requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.PATCH], order = 100)
    fun handlePatch(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles HEAD requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.HEAD], order = 100)
    fun handleHead(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Handles OPTIONS requests.
     *
     * @param routingContext The Vert.x routing context
     */
    @Route(path = "/*", methods = [Route.HttpMethod.OPTIONS], order = 100)
    fun handleOptions(routingContext: RoutingContext) {
        handleRequest(routingContext)
    }

    /**
     * Main request handler that processes all HTTP methods.
     * Includes metrics collection for request tracking.
     *
     * @param routingContext The Vert.x routing context
     */
    private fun handleRequest(routingContext: RoutingContext) {
        val timer = Timer.start(meterRegistry)
        val request = routingContext.request()
        val response = routingContext.response()

        // Extract and validate Host header
        val host = request.getHeader("Host") ?: run {
            recordMetrics(timer, "unknown", 400)
            sendError(response, 400, MISSING_HOST_MESSAGE)
            return
        }

        // Parse subdomain
        val subdomain = extractSubdomain(host)
        if (subdomain.isBlank()) {
            recordMetrics(timer, "unknown", 400)
            sendError(response, 400, INVALID_SUBDOMAIN_MESSAGE)
            return
        }

        // Look up tunnel in registry
        val tunnel = tunnelRegistry.getBySubdomain(subdomain)

        // Handle tunnel not found
        if (tunnel == null) {
            logger.warn("Tunnel not found for subdomain: {}", subdomain)
            recordMetrics(timer, subdomain, 404)
            meterRegistry.counter(
                "relay.tunnel.not_found",
                "subdomain", subdomain
            ).increment()
            sendError(response, 404, "$NOT_FOUND_MESSAGE: $subdomain")
            return
        }

        // Handle inactive tunnel
        if (!tunnel.isActive()) {
            logger.warn("Tunnel not active for subdomain: {}", subdomain)
            recordMetrics(timer, subdomain, 503)
            meterRegistry.counter(
                "relay.tunnel.inactive",
                "subdomain", subdomain
            ).increment()
            sendError(response, 503, "$SERVICE_UNAVAILABLE_MESSAGE: $subdomain")
            return
        }

        // Validate HTTP method
        val method = request.method()
        if (method !in ALLOWED_METHODS) {
            recordMetrics(timer, subdomain, 405)
            sendError(response, 405, "$METHOD_NOT_ALLOWED_MESSAGE: ${method.name()}")
            return
        }

        // Increment active requests counter
        meterRegistry.counter(
            "relay.request.received",
            "subdomain", subdomain,
            "method", method.name()
        ).increment()

        // Generate correlation ID
        val correlationId = UUID.randomUUID().toString()

        // Handle request with body for POST, PUT, PATCH
        val hasBody = method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH

        if (hasBody) {
            handleRequestWithBody(routingContext, tunnel.session.id, correlationId, subdomain, timer)
        } else {
            handleRequestWithoutBody(routingContext, tunnel.session.id, correlationId, subdomain, timer)
        }
    }

    /**
     * Handles requests that may have a body (POST, PUT, PATCH).
     *
     * @param routingContext The routing context
     * @param sessionId The WebSocket session ID
     * @param correlationId The correlation ID
     * @param subdomain The subdomain for the tunnel
     * @param timer The metrics timer for tracking request duration
     */
    private fun handleRequestWithBody(
        routingContext: RoutingContext,
        sessionId: String,
        correlationId: String,
        subdomain: String,
        timer: Timer.Sample
    ) {
        val request = routingContext.request()
        val response = routingContext.response()
        val maxBodySize = relayConfig.maxBodySize()

        request.bodyHandler { bodyBuffer ->
            // Check body size
            if (bodyBuffer.length() > maxBodySize) {
                recordMetrics(timer, subdomain, 413)
                sendError(response, 413, BODY_TOO_LARGE_MESSAGE)
                return@bodyHandler
            }

            // Build request payload
            val requestPayload = try {
                buildRequestPayload(routingContext, bodyBuffer.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                recordMetrics(timer, subdomain, 500)
                sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: ${e.message}")
                return@bodyHandler
            }

            // Send request and await response
            sendRequestAndAwaitResponse(routingContext, sessionId, correlationId, subdomain, requestPayload, timer)
        }

        request.exceptionHandler { error ->
            recordMetrics(timer, subdomain, 500)
            sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: ${error.message}")
        }
    }

    /**
     * Handles requests without a body (GET, DELETE, HEAD, OPTIONS).
     *
     * @param routingContext The routing context
     * @param sessionId The WebSocket session ID
     * @param correlationId The correlation ID
     * @param subdomain The subdomain for the tunnel
     * @param timer The metrics timer for tracking request duration
     */
    private fun handleRequestWithoutBody(
        routingContext: RoutingContext,
        sessionId: String,
        correlationId: String,
        subdomain: String,
        timer: Timer.Sample
    ) {
        val response = routingContext.response()

        // Build request payload without body
        val requestPayload = try {
            buildRequestPayload(routingContext, null)
        } catch (e: Exception) {
            recordMetrics(timer, subdomain, 500)
            sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: ${e.message}")
            return
        }

        // Send request and await response
        sendRequestAndAwaitResponse(routingContext, sessionId, correlationId, subdomain, requestPayload, timer)
    }

    /**
     * Extracts the subdomain from the Host header by removing the base domain suffix.
     *
     * @param host The Host header value (e.g., "abc123.example.com:8080")
     * @return The subdomain (e.g., "abc123")
     */
    private fun extractSubdomain(host: String): String {
        // Remove port if present
        val hostWithoutPort = host.substringBefore(':')

        // Get base domain from config
        val baseDomain = relayConfig.domain()

        // Remove base domain suffix
        return when {
            hostWithoutPort == baseDomain -> ""
            hostWithoutPort.endsWith(".$baseDomain") -> {
                hostWithoutPort.removeSuffix(".$baseDomain")
            }
            else -> hostWithoutPort.substringBefore('.')
        }
    }

    /**
     * Builds a RequestPayload from the routing context.
     *
     * @param routingContext The routing context
     * @param body The request body (may be null)
     * @return The constructed RequestPayload
     */
    private fun buildRequestPayload(
        routingContext: RoutingContext,
        body: String?
    ): RequestPayload {
        val request = routingContext.request()
        val method = request.method().name()

        // Extract headers
        val headers = mutableMapOf<String, String>()
        request.headers().forEach { entry ->
            headers[entry.key] = entry.value
        }

        // Extract query parameters
        val queryParams = mutableMapOf<String, String>()
        request.params().forEach { entry ->
            // Only include actual query params, not path params
            if (!routingContext.pathParams().containsKey(entry.key)) {
                queryParams[entry.key] = entry.value
            }
        }

        return RequestPayload(
            method = method,
            path = request.path() ?: "/",
            query = queryParams.ifEmpty { null },
            headers = headers,
            body = body
        )
    }

    /**
     * Sends the request via WebSocket and awaits the response.
     * Implements 30-second timeout handling and request/response correlation.
     *
     * @param routingContext The routing context for sending the response
     * @param sessionId The WebSocket session ID
     * @param correlationId The correlation ID for tracking
     * @param subdomain The subdomain for looking up the tunnel
     * @param requestPayload The request payload to send
     * @param timer The metrics timer for tracking request duration
     */
    private fun sendRequestAndAwaitResponse(
        routingContext: RoutingContext,
        sessionId: String,
        correlationId: String,
        subdomain: String,
        requestPayload: RequestPayload,
        timer: Timer.Sample
    ) {
        val response = routingContext.response()

        // Look up tunnel again to ensure it's still active
        val tunnel = tunnelRegistry.getBySubdomain(subdomain)

        if (tunnel == null || !tunnel.isActive()) {
            recordMetrics(timer, subdomain, 503)
            sendError(response, 503, SERVICE_UNAVAILABLE_MESSAGE)
            return
        }

        // Verify session ID matches
        if (tunnel.session.id != sessionId) {
            recordMetrics(timer, subdomain, 503)
            sendError(response, 503, SERVICE_UNAVAILABLE_MESSAGE)
            return
        }

        // Create envelope
        val envelope = try {
            createRequestEnvelope(correlationId, requestPayload)
        } catch (e: Exception) {
            recordMetrics(timer, subdomain, 500)
            sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: ${e.message}")
            return
        }

        // Create pending request future
        val responseFuture = CompletableFuture<ResponsePayload>()

        // Schedule timeout (30 seconds)
        val timeoutMillis = relayConfig.requestTimeout().toMillis()
        val timeoutTask = scheduledExecutor.schedule({
            responseFuture.completeExceptionally(
                java.util.concurrent.TimeoutException(TIMEOUT_MESSAGE)
            )
        }, timeoutMillis, TimeUnit.MILLISECONDS)

        // Register pending request
        val pendingRequest = PendingRequest(correlationId, responseFuture, timeoutTask)
        if (!tunnelRegistry.registerPendingRequest(correlationId, pendingRequest)) {
            timeoutTask.cancel(false)
            recordMetrics(timer, subdomain, 500)
            sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: Duplicate correlation ID")
            return
        }

        try {
            // Serialize and send envelope
            val envelopeJson = objectMapper.writeValueAsString(envelope)

            tunnel.session.asyncRemote.sendText(envelopeJson) { sendResult ->
                if (!sendResult.isOK) {
                    tunnelRegistry.unregisterPendingRequest(correlationId)
                    timeoutTask.cancel(false)
                    responseFuture.completeExceptionally(
                        Exception("Failed to send request via WebSocket: ${sendResult.exception?.message}")
                    )
                }
            }

            // Wait for response and stream back
            responseFuture.whenComplete { responsePayload, error ->
                tunnelRegistry.unregisterPendingRequest(correlationId)

                routingContext.vertx().runOnContext {
                    when {
                        error != null -> {
                            when (error) {
                                is java.util.concurrent.TimeoutException -> {
                                    logger.warn("Request timeout for subdomain={}: correlationId={}", subdomain, correlationId)
                                    recordMetrics(timer, subdomain, 504)
                                    sendError(response, 504, TIMEOUT_MESSAGE)
                                }
                                else -> {
                                    logger.error("Error from tunnel for subdomain={}: {}", subdomain, error.message)
                                    recordMetrics(timer, subdomain, 502)
                                    sendError(response, 502, "Error from tunnel: ${error.message}")
                                }
                            }
                        }
                        else -> {
                            recordMetrics(timer, subdomain, responsePayload.statusCode)
                            streamResponse(response, responsePayload)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tunnelRegistry.unregisterPendingRequest(correlationId)
            timeoutTask.cancel(false)
            recordMetrics(timer, subdomain, 500)
            sendError(response, 500, "$INTERNAL_ERROR_MESSAGE: ${e.message}")
        }
    }

    /**
     * Creates an Envelope with REQUEST type containing the request payload.
     *
     * @param correlationId The unique correlation ID
     * @param requestPayload The request payload
     * @return The constructed Envelope
     */
    private fun createRequestEnvelope(
        correlationId: String,
        requestPayload: RequestPayload
    ): Envelope {
        val payloadNode = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(requestPayload)

        return Envelope(
            correlationId = correlationId,
            type = MessageType.REQUEST,
            payload = payloadNode
        )
    }

    /**
     * Streams the response payload back to the original requester.
     *
     * @param response The HTTP response
     * @param payload The response payload from the tunnel
     */
    private fun streamResponse(response: HttpServerResponse, payload: ResponsePayload) {
        // Check if response is already ended
        if (response.ended()) return

        // Set status code
        response.statusCode = payload.statusCode

        // Set headers
        payload.headers.forEach { (name, value) ->
            // Skip hop-by-hop headers
            if (!isHopByHopHeader(name)) {
                response.putHeader(name, value)
            }
        }

        // Write body
        if (payload.body != null) {
            response.end(payload.body)
        } else {
            response.end()
        }
    }

    /**
     * Sends an error response.
     *
     * @param response The HTTP response
     * @param statusCode The HTTP status code
     * @param message The error message
     */
    private fun sendError(response: HttpServerResponse, statusCode: Int, message: String) {
        if (response.ended()) return

        response.statusCode = statusCode
        response.putHeader("Content-Type", "text/plain")
        response.end(message)
    }

    /**
     * Checks if a header is a hop-by-hop header that should not be forwarded.
     *
     * @param name The header name
     * @return true if the header is hop-by-hop
     */
    private fun isHopByHopHeader(name: String): Boolean {
        val hopByHopHeaders = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade"
        )
        return name.lowercase() in hopByHopHeaders
    }

    /**
     * Records metrics for the request.
     *
     * @param timer The started timer sample
     * @param subdomain The target subdomain
     * @param statusCode The HTTP status code
     */
    private fun recordMetrics(timer: Timer.Sample, subdomain: String, statusCode: Int) {
        timer.stop(
            Timer.builder("relay.request.duration")
                .tag("subdomain", subdomain)
                .tag("status", statusCode.toString())
                .register(meterRegistry)
        )
    }
}

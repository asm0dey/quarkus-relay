package org.relay.server.forwarder

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
 * Service for forwarding HTTP requests to tunnels via WebSocket.
 * Handles request serialization, envelope creation, and response correlation.
 */
@ApplicationScoped
class RequestForwarder @Inject constructor(
    private val tunnelRegistry: TunnelRegistry,
    private val relayConfig: RelayConfig,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {

    private val logger = LoggerFactory.getLogger(RequestForwarder::class.java)

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val GATEWAY_TIMEOUT_STATUS = 504
        private const val BAD_GATEWAY_STATUS = 502
        private const val SERVICE_UNAVAILABLE_STATUS = 503
        private val scheduledExecutor = Executors.newScheduledThreadPool(4)
    }

    /**
     * Result of a forwarded request containing either a successful response or error information.
     */
    data class ForwardResult(
        val success: Boolean,
        val response: ResponsePayload? = null,
        val errorStatusCode: Int? = null,
        val errorMessage: String? = null
    )

    /**
     * Forwards an HTTP request to a tunnel identified by subdomain.
     * Serializes the request, sends it via WebSocket, and waits for the response.
     *
     * @param subdomain The subdomain identifying the target tunnel
     * @param requestPayload The HTTP request payload to forward
     * @param timeoutSeconds Timeout in seconds (default 30s)
     * @return ForwardResult containing either the response or error information
     */
    fun forwardRequest(
        subdomain: String,
        requestPayload: RequestPayload,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): ForwardResult {
        val timer = Timer.start(meterRegistry)
        val correlationId = generateCorrelationId()

        logger.debug("Forwarding request to subdomain={}: method={}, path={}",
            subdomain, requestPayload.method, requestPayload.path)

        return try {
            // Look up the tunnel
            val tunnel = tunnelRegistry.getBySubdomain(subdomain)
                ?: return ForwardResult(
                    success = false,
                    errorStatusCode = SERVICE_UNAVAILABLE_STATUS,
                    errorMessage = "Tunnel not found for subdomain: $subdomain"
                ).also {
                    recordMetrics(timer, subdomain, false, SERVICE_UNAVAILABLE_STATUS)
                }

            // Check if tunnel is active
            if (!tunnel.isActive()) {
                return ForwardResult(
                    success = false,
                    errorStatusCode = SERVICE_UNAVAILABLE_STATUS,
                    errorMessage = "Tunnel is not active for subdomain: $subdomain"
                ).also {
                    recordMetrics(timer, subdomain, false, SERVICE_UNAVAILABLE_STATUS)
                }
            }

            // Create and send the request envelope
            val envelope = createRequestEnvelope(correlationId, requestPayload)
            val envelopeJson = objectMapper.writeValueAsString(envelope)

            // Create future for response
            val responseFuture = CompletableFuture<ResponsePayload>()

            // Schedule timeout
            val timeoutTask = scheduledExecutor.schedule({
                responseFuture.completeExceptionally(
                    RequestTimeoutException("Request $correlationId timed out after ${timeoutSeconds}s")
                )
            }, timeoutSeconds, TimeUnit.SECONDS)

            // Register pending request
            val pendingRequest = PendingRequest(correlationId, responseFuture, timeoutTask)
            if (!tunnelRegistry.registerPendingRequest(correlationId, pendingRequest)) {
                timeoutTask.cancel(false)
                return ForwardResult(
                    success = false,
                    errorStatusCode = SERVICE_UNAVAILABLE_STATUS,
                    errorMessage = "Failed to register pending request: duplicate correlation ID"
                ).also {
                    recordMetrics(timer, subdomain, false, SERVICE_UNAVAILABLE_STATUS)
                }
            }

            // Send via WebSocket
            val sendResult = sendViaWebSocket(tunnel.session, envelopeJson)

            if (!sendResult) {
                tunnelRegistry.unregisterPendingRequest(correlationId)
                timeoutTask.cancel(false)
                return ForwardResult(
                    success = false,
                    errorStatusCode = BAD_GATEWAY_STATUS,
                    errorMessage = "Failed to send request via WebSocket"
                ).also {
                    recordMetrics(timer, subdomain, false, BAD_GATEWAY_STATUS)
                }
            }

            // Wait for response
            val response = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS)

            tunnelRegistry.unregisterPendingRequest(correlationId)

            ForwardResult(
                success = true,
                response = response
            ).also {
                recordMetrics(timer, subdomain, true, response.statusCode)
            }

        } catch (e: java.util.concurrent.TimeoutException) {
            tunnelRegistry.unregisterPendingRequest(correlationId)
            logger.warn("Request timeout for subdomain={}: correlationId={}", subdomain, correlationId)
            ForwardResult(
                success = false,
                errorStatusCode = GATEWAY_TIMEOUT_STATUS,
                errorMessage = "Request timed out waiting for response from tunnel"
            ).also {
                recordMetrics(timer, subdomain, false, GATEWAY_TIMEOUT_STATUS)
            }
        } catch (e: Exception) {
            tunnelRegistry.unregisterPendingRequest(correlationId)
            logger.error("Error forwarding request to subdomain={}", subdomain, e)
            ForwardResult(
                success = false,
                errorStatusCode = BAD_GATEWAY_STATUS,
                errorMessage = "Error forwarding request: ${e.message}"
            ).also {
                recordMetrics(timer, subdomain, false, BAD_GATEWAY_STATUS)
            }
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
     * Sends the envelope JSON via WebSocket session.
     *
     * @param session The WebSocket session
     * @param envelopeJson The serialized envelope
     * @return true if send was successful, false otherwise
     */
    private fun sendViaWebSocket(session: jakarta.websocket.Session, envelopeJson: String): Boolean {
        return try {
            val asyncRemote = session.asyncRemote
            val future = CompletableFuture<Boolean>()

            asyncRemote.sendText(envelopeJson) { result ->
                if (result.isOK) {
                    future.complete(true)
                } else {
                    future.completeExceptionally(
                        result.exception ?: RuntimeException("Failed to send message")
                    )
                }
            }

            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Error sending via WebSocket", e)
            false
        }
    }

    /**
     * Generates a unique correlation ID for requests.
     *
     * @return A unique correlation ID string
     */
    private fun generateCorrelationId(): String {
        return "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    /**
     * Records metrics for the forward operation.
     *
     * @param timer The started timer
     * @param subdomain The target subdomain
     * @param success Whether the request was successful
     * @param statusCode The HTTP status code (or error code)
     */
    private fun recordMetrics(timer: Timer.Sample, subdomain: String, success: Boolean, statusCode: Int) {
        timer.stop(
            Timer.builder("relay.request.forward")
                .tag("subdomain", subdomain)
                .tag("success", success.toString())
                .tag("status", statusCode.toString())
                .register(meterRegistry)
        )

        // Increment counter
        meterRegistry.counter(
            "relay.request.total",
            "subdomain", subdomain,
            "success", success.toString(),
            "status", statusCode.toString()
        ).increment()
    }
}

/**
 * Exception thrown when a request times out.
 */
class RequestTimeoutException(message: String) : RuntimeException(message)

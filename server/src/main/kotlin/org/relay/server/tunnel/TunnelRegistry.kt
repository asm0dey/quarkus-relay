package org.relay.server.tunnel

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.relay.shared.protocol.ResponsePayload
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton bean for managing active tunnel connections.
 * Provides thread-safe operations for registering, unregistering, and querying tunnels.
 *
 * This class uses a [ConcurrentHashMap] internally to ensure thread-safe operations
 * without requiring explicit synchronization on most methods.
 */
@ApplicationScoped
class TunnelRegistry {

    private val logger = LoggerFactory.getLogger(TunnelRegistry::class.java)

    private val tunnels = ConcurrentHashMap<String, TunnelConnection>()
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val requestsPerSubdomain = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Registers a new tunnel connection.
     *
     * @param subdomain The subdomain to register the tunnel under
     * @param connection The tunnel connection to register
     * @return true if registration succeeded, false if a tunnel with the given subdomain already exists
     */
    fun register(subdomain: String, connection: TunnelConnection): Boolean {
        logger.debug("Registering tunnel: subdomain={}", subdomain)
        requestsPerSubdomain[subdomain] = ConcurrentHashMap.newKeySet()
        val success = tunnels.putIfAbsent(subdomain, connection) == null
        if (success) {
            logger.info("Successfully registered tunnel: subdomain={}", subdomain)
        } else {
            logger.warn("Failed to register tunnel: subdomain={} already exists", subdomain)
        }
        return success
    }

    /**
     * Unregisters a tunnel connection by subdomain.
     *
     * @param subdomain The subdomain of the tunnel to unregister
     * @return true if a tunnel was found and removed, false if no tunnel existed for the subdomain
     */
    fun unregister(subdomain: String): Boolean {
        val tunnel = tunnels.remove(subdomain)
        if (tunnel != null) {
            logger.info("Unregistering tunnel: subdomain={}", subdomain)
            val requestIds = requestsPerSubdomain.remove(subdomain)
            requestIds?.forEach { correlationId ->
                logger.debug("Cancelling pending request due to tunnel unregistration: correlationId={}", correlationId)
                completePendingRequestExceptionally(correlationId, RequestCancelledException("Tunnel disconnected"))
            }
            // Also close the tunnel connection (which closes its proxies)
            tunnel.close("Tunnel unregistered")
            return true
        }
        logger.debug("Attempted to unregister non-existent tunnel: subdomain={}", subdomain)
        return false
    }

    /**
     * Gets a tunnel connection by its subdomain.
     *
     * @param subdomain The subdomain to look up
     * @return The tunnel connection if found, null otherwise
     */
    fun getBySubdomain(subdomain: String): TunnelConnection? {
        return tunnels[subdomain]
    }

    /**
     * Gets all currently active tunnel connections.
     *
     * @return A list of all active tunnel connections
     */
    fun getAllActive(): List<TunnelConnection> {
        return tunnels.values.toList()
    }

    /**
     * Checks if a tunnel exists for the given subdomain.
     *
     * @param subdomain The subdomain to check
     * @return true if a tunnel exists for the subdomain, false otherwise
     */
    fun hasTunnel(subdomain: String): Boolean {
        return tunnels.containsKey(subdomain)
    }

    /**
     * Gets the number of currently registered tunnels.
     *
     * @return The count of active tunnels
     */
    fun size(): Int {
        return tunnels.size
    }

    /**
     * Clears all registered tunnels.
     * This is typically called during shutdown to clean up resources.
     */
    fun clear() {
        tunnels.clear()
    }

    /**
     * Registers a pending request for correlation tracking.
     *
     * @param subdomain The subdomain for the request
     * @param correlationId The correlation ID for the request
     * @param pendingRequest The pending request to register
     * @return true if registration succeeded, false if a request with the given correlationId already exists
     */
    fun registerPendingRequest(subdomain: String, correlationId: String, pendingRequest: PendingRequest): Boolean {
        logger.debug("Registering pending request: subdomain={}, correlationId={}", subdomain, correlationId)
        val registered = pendingRequests.putIfAbsent(correlationId, pendingRequest) == null
        if (registered) {
            requestsPerSubdomain[subdomain]?.add(correlationId)
        } else {
            logger.warn("Failed to register pending request: correlationId={} already exists", correlationId)
        }
        return registered
    }

    /**
     * Unregisters a pending request by correlation ID.
     *
     * @param subdomain The subdomain for the request
     * @param correlationId The correlation ID of the request to unregister
     * @return true if a request was found and removed, false if no request existed
     */
    fun unregisterPendingRequest(subdomain: String, correlationId: String): Boolean {
        val removed = pendingRequests.remove(correlationId) != null
        if (removed) {
            requestsPerSubdomain[subdomain]?.remove(correlationId)
        }
        return removed
    }

    /**
     * Gets a pending request by its correlation ID.
     *
     * @param correlationId The correlation ID to look up
     * @return The pending request if found, null otherwise
     */
    fun getPendingRequest(correlationId: String): PendingRequest? {
        return pendingRequests[correlationId]
    }

    /**
     * Completes a pending request with the given response.
     *
     * @param correlationId The correlation ID of the request
     * @param response The response payload
     * @return true if the request was found and completed, false otherwise
     */
    fun completePendingRequest(correlationId: String, response: ResponsePayload): Boolean {
        val request = pendingRequests.remove(correlationId)
        if (request != null) {
            logger.debug("Completing pending request: correlationId={}", correlationId)
            return request.complete(response)
        }
        logger.debug("Attempted to complete non-existent pending request: correlationId={}", correlationId)
        return false
    }

    /**
     * Completes a pending request exceptionally.
     *
     * @param correlationId The correlation ID of the request
     * @param exception The exception to complete with
     * @return true if the request was found and completed exceptionally, false otherwise
     */
    fun completePendingRequestExceptionally(correlationId: String, exception: Throwable): Boolean {
        val request = pendingRequests.remove(correlationId)
        return request?.completeExceptionally(exception) ?: false
    }

    /**
     * Gets the number of currently pending requests.
     *
     * @return The count of pending requests
     */
    fun pendingRequestCount(): Int {
        return pendingRequests.size
    }

    /**
     * Cleanup method called before the application is destroyed.
     * Closes all active tunnel sessions and clears the registry.
     */
    @PreDestroy
    fun shutdown() {
        tunnels.values.forEach { connection ->
            try {
                connection.close("Server shutting down")
            } catch (e: Exception) {
                // Log and continue closing other connections
                // In a real implementation, use proper logging framework
                println("Error closing tunnel ${connection.subdomain}: ${e.message}")
            }
        }
        tunnels.clear()
        
        // Cancel all pending requests
        pendingRequests.values.forEach { it.cancel(reason = "Server shutting down") }
        pendingRequests.clear()
    }
}

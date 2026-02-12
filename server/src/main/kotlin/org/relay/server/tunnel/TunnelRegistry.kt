package org.relay.server.tunnel

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.relay.shared.protocol.ResponsePayload
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

    private val tunnels = ConcurrentHashMap<String, TunnelConnection>()
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /**
     * Registers a new tunnel connection.
     *
     * @param subdomain The subdomain to register the tunnel under
     * @param connection The tunnel connection to register
     * @return true if registration succeeded, false if a tunnel with the given subdomain already exists
     */
    fun register(subdomain: String, connection: TunnelConnection): Boolean {
        return tunnels.putIfAbsent(subdomain, connection) == null
    }

    /**
     * Unregisters a tunnel connection by subdomain.
     *
     * @param subdomain The subdomain of the tunnel to unregister
     * @return true if a tunnel was found and removed, false if no tunnel existed for the subdomain
     */
    fun unregister(subdomain: String): Boolean {
        return tunnels.remove(subdomain) != null
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
     * @param correlationId The correlation ID for the request
     * @param pendingRequest The pending request to register
     * @return true if registration succeeded, false if a request with the given correlationId already exists
     */
    fun registerPendingRequest(correlationId: String, pendingRequest: PendingRequest): Boolean {
        return pendingRequests.putIfAbsent(correlationId, pendingRequest) == null
    }

    /**
     * Unregisters a pending request by correlation ID.
     *
     * @param correlationId The correlation ID of the request to unregister
     * @return true if a request was found and removed, false if no request existed
     */
    fun unregisterPendingRequest(correlationId: String): Boolean {
        return pendingRequests.remove(correlationId) != null
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
        return request?.complete(response) ?: false
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

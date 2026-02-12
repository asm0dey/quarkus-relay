package org.relay.server.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Configuration interface for the relay server.
 * Maps configuration properties from the "relay" prefix in application.yaml.
 */
@ConfigMapping(prefix = "relay")
interface RelayConfig {

    /**
     * The base domain for the relay service.
     * Used to construct full URLs for tunnels (e.g., subdomain.domain.com).
     */
    fun domain(): String

    /**
     * Set of valid secret keys for client authentication.
     * Clients must provide one of these keys to establish a tunnel.
     */
    fun secretKeys(): Set<String>

    /**
     * Timeout duration for pending requests waiting for a response.
     * Requests that don't receive a response within this duration will be timed out.
     */
    fun requestTimeout(): Duration

    /**
     * Maximum allowed size for request/response bodies in bytes.
     * Requests or responses exceeding this size will be rejected.
     */
    fun maxBodySize(): Long

    /**
     * Length of generated subdomains in characters.
     * Default is 12 characters.
     */
    @WithDefault("12")
    fun subdomainLength(): Int

    /**
     * Shutdown mode for the server.
     * - "graceful": Waits for active requests to complete before shutting down
     * - "immediate": Forces immediate shutdown
     */
    @WithDefault("graceful")
    fun shutdownMode(): String

    /**
     * Timeout for graceful shutdown.
     * If active requests don't complete within this duration, forced shutdown occurs.
     */
    @WithDefault("PT30S")
    fun gracefulShutdownTimeout(): Duration
}

package org.relay.server

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.relay.server.config.RelayConfig
import org.relay.server.routing.SubdomainRoutingHandler
import org.relay.server.tunnel.TunnelRegistry
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Main application class for the relay server.
 * Handles server startup, configuration logging, and graceful shutdown.
 */
@QuarkusMain
@ApplicationScoped
class Application @Inject constructor(
    private val relayConfig: RelayConfig,
    private val tunnelRegistry: TunnelRegistry,
    private val subdomainRoutingHandler: SubdomainRoutingHandler
) {

    companion object {
        private val logger = LoggerFactory.getLogger(Application::class.java)
        private const val APP_NAME = "relay-server"
        private const val APP_VERSION = "1.0.0"
    }

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    lateinit var port: String

    /**
     * Called when the application starts up.
     * Logs the startup banner and configuration summary.
     */
    fun onStartup(@Observes event: StartupEvent) {
        logStartupBanner()
        logConfigurationSummary()
    }

    /**
     * Called when the application shuts down.
     * Performs graceful shutdown including closing WebSocket connections
     * and waiting for in-flight requests.
     */
    fun onShutdown(@Observes event: ShutdownEvent) {
        logger.info("Initiating server shutdown...")

        val shutdownMode = relayConfig.shutdownMode()
        val gracefulTimeout = relayConfig.gracefulShutdownTimeout()

        if (shutdownMode == "immediate") {
            logger.info("Immediate shutdown mode - closing all connections immediately")
            immediateShutdown()
        } else {
            logger.info("Graceful shutdown mode - waiting up to {} for in-flight requests", gracefulTimeout)
            gracefulShutdown(gracefulTimeout)
        }

        logger.info("Server shutdown complete")
    }

    /**
     * Main entry point for the application.
     *
     * @param args Command line arguments
     */
    fun main(args: Array<String>) {
        Quarkus.run(*args)
    }

    /**
     * Logs the startup banner with application name and version.
     */
    private fun logStartupBanner() {
        val banner = buildString {
            appendLine()
            appendLine("╔═══════════════════════════════════════════════════════════════╗")
            appendLine("║                                                               ║")
            appendLine("║              $APP_NAME v$APP_VERSION                             ║")
            appendLine("║              Secure HTTP Tunnel Relay Server                  ║")
            appendLine("║                                                               ║")
            appendLine("╚═══════════════════════════════════════════════════════════════╝")
        }
        logger.info(banner)
    }

    /**
     * Logs the configuration summary including domain, port, secret keys count,
     * max body size, and request timeout.
     */
    private fun logConfigurationSummary() {
        logger.info("Configuration Summary:")
        logger.info("  Domain: {}", relayConfig.domain())
        logger.info("  Port: {}", port)
        logger.info("  Secret Keys Count: {}", relayConfig.secretKeys().size)
        logger.info("  Max Body Size: {} bytes", relayConfig.maxBodySize())
        logger.info("  Request Timeout: {}", relayConfig.requestTimeout())
        logger.info("  Shutdown Mode: {}", relayConfig.shutdownMode())
        logger.info("  Graceful Shutdown Timeout: {}", relayConfig.gracefulShutdownTimeout())
    }

    /**
     * Performs immediate shutdown by closing all WebSocket connections immediately.
     */
    private fun immediateShutdown() {
        val activeTunnels = tunnelRegistry.getAllActive()
        logger.info("Closing {} active tunnel connections immediately", activeTunnels.size)

        activeTunnels.forEach { connection ->
            try {
                connection.close("Server shutting down (immediate)")
                logger.debug("Closed tunnel for subdomain: {}", connection.subdomain)
            } catch (e: Exception) {
                logger.warn("Error closing tunnel for subdomain {}: {}", connection.subdomain, e.message)
            }
        }

        tunnelRegistry.clear()
        logger.info("All tunnel connections closed")
    }

    /**
     * Performs graceful shutdown by waiting for in-flight requests to complete.
     *
     * @param timeout Maximum time to wait for in-flight requests
     */
    private fun gracefulShutdown(timeout: Duration) {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeout.toMillis()

        // First, close all WebSocket connections to prevent new requests
        val activeTunnels = tunnelRegistry.getAllActive()
        logger.info("Closing {} active WebSocket connections", activeTunnels.size)

        activeTunnels.forEach { connection ->
            try {
                connection.close("Server shutting down (graceful)")
                logger.debug("Closed WebSocket for subdomain: {}", connection.subdomain)
            } catch (e: Exception) {
                logger.warn("Error closing WebSocket for subdomain {}: {}", connection.subdomain, e.message)
            }
        }

        // Wait for pending requests to complete
        var pendingCount = tunnelRegistry.pendingRequestCount()
        if (pendingCount > 0) {
            logger.info("Waiting for {} in-flight requests to complete (timeout: {})", pendingCount, timeout)

            while (pendingCount > 0 && (System.currentTimeMillis() - startTime) < timeoutMillis) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("Graceful shutdown interrupted")
                    break
                }
                pendingCount = tunnelRegistry.pendingRequestCount()
            }

            if (pendingCount > 0) {
                logger.warn("Graceful shutdown timeout reached with {} pending requests remaining", pendingCount)
            } else {
                logger.info("All in-flight requests completed")
            }
        } else {
            logger.info("No in-flight requests to wait for")
        }

        // Clear the registry
        tunnelRegistry.clear()
        logger.info("Tunnel registry cleared")
    }
}

package org.relay.client

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.annotations.QuarkusMain
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.relay.client.config.ClientConfig
import org.relay.client.retry.ReconnectionHandler
import org.relay.client.websocket.WebSocketClientEndpoint
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@QuarkusMain
@ApplicationScoped
class TunnelClient @Inject constructor(
    private val clientConfig: ClientConfig,
    private val clientEndpoint: WebSocketClientEndpoint,
    private val reconnectionHandler: ReconnectionHandler
) : QuarkusApplication {

    private val logger = LoggerFactory.getLogger(TunnelClient::class.java)
    private val shutdownRequested = AtomicBoolean(false)
    private val connectionLatch = CountDownLatch(1)
    private var exitCode = 0

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Quarkus.run(TunnelClient::class.java, *args)
        }
    }

    fun onStart(@Observes event: StartupEvent) {
        logger.info("╔════════════════════════════════════════════════════════╗")
        logger.info("║          Relay Tunnel Client v1.0.0                  ║")
        logger.info("╚════════════════════════════════════════════════════════╝")
        logger.info("Configuration:")
        logger.info("  Server URL: ${clientConfig.serverUrl()}")
        logger.info("  Local URL: ${clientConfig.localUrl()}")
        logger.info("  Reconnection: enabled=${clientConfig.reconnect().enabled()}")
    }

    fun onShutdown(@Observes event: ShutdownEvent) {
        logger.info("Shutdown signal received, disconnecting...")
        shutdownRequested.set(true)
        clientEndpoint.close()
    }

    override fun run(args: Array<String>): Int {
        // Parse command line args
        parseArgs(args)

        if (!validateConfiguration()) {
            return 1
        }

        // Start connection loop
        while (!shutdownRequested.get()) {
            try {
                if (connect()) {
                    // Connection successful, wait for disconnect
                    waitForDisconnect()
                    
                    if (shutdownRequested.get()) {
                        break
                    }
                    
                    // Connection lost, check if we should reconnect
                    if (!reconnectionHandler.shouldReconnect()) {
                        logger.error("Connection lost and reconnection is disabled")
                        exitCode = 2
                        break
                    }
                    
                    // Calculate and wait for next retry
                    val nextDelay = reconnectionHandler.calculateNextDelay()
                    logger.info("Reconnecting in ${nextDelay.seconds} seconds...")
                    reconnectionHandler.recordAttempt()
                    
                    Thread.sleep(nextDelay.toMillis())
                } else {
                    // Connection failed
                    if (!reconnectionHandler.shouldReconnect()) {
                        logger.error("Connection failed and reconnection is disabled")
                        exitCode = 2
                        break
                    }
                    
                    val nextDelay = reconnectionHandler.calculateNextDelay()
                    logger.info("Retrying in ${nextDelay.seconds} seconds...")
                    reconnectionHandler.recordAttempt()
                    
                    Thread.sleep(nextDelay.toMillis())
                }
            } catch (e: InterruptedException) {
                logger.info("Connection loop interrupted")
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                logger.error("Unexpected error in connection loop", e)
                if (!reconnectionHandler.shouldReconnect()) {
                    exitCode = 2
                    break
                }
                Thread.sleep(1000)
            }
        }

        logger.info("Tunnel client shutting down with exit code $exitCode")
        return exitCode
    }

    private fun parseArgs(args: Array<String>) {
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--server-url", "-s" -> {
                    if (i + 1 < args.size) {
                        System.setProperty("relay.client.server-url", args[i + 1])
                        i += 2
                    } else {
                        i++
                    }
                }
                "--secret-key", "-k" -> {
                    if (i + 1 < args.size) {
                        System.setProperty("relay.client.secret-key", args[i + 1])
                        i += 2
                    } else {
                        i++
                    }
                }
                "--local-url", "-l" -> {
                    if (i + 1 < args.size) {
                        System.setProperty("relay.client.local-url", args[i + 1])
                        i += 2
                    } else {
                        i++
                    }
                }
                "--subdomain", "-d" -> {
                    if (i + 1 < args.size) {
                        System.setProperty("relay.client.subdomain", args[i + 1])
                        i += 2
                    } else {
                        i++
                    }
                }
                "--help", "-h" -> {
                    printHelp()
                    System.exit(0)
                }
                else -> i++
            }
        }
    }

    private fun validateConfiguration(): Boolean {
        if (clientConfig.serverUrl().isBlank()) {
            logger.error("Server URL is required (use --server-url or RELAY_SERVER_URL)")
            return false
        }
        if (clientConfig.secretKey().isBlank()) {
            logger.error("Secret key is required (use --secret-key or RELAY_SECRET_KEY)")
            return false
        }
        if (clientConfig.localUrl().isBlank()) {
            logger.error("Local URL is required (use --local-url or RELAY_LOCAL_URL)")
            return false
        }
        return true
    }

    private fun connect(): Boolean {
        return try {
            logger.info("Connecting to ${clientConfig.serverUrl()}...")
            
            val uri = URI(clientConfig.serverUrl())
            val container = jakarta.websocket.ContainerProvider.getWebSocketContainer()
            
            container.connectToServer(clientEndpoint, uri)
            
            // Wait for connection to be established (up to 10 seconds)
            var attempts = 0
            while (!clientEndpoint.isConnected() && attempts < 100) {
                Thread.sleep(100)
                attempts++
            }
            
            if (clientEndpoint.isConnected()) {
                logger.info("WebSocket connection established")
                reconnectionHandler.reset()
                true
            } else {
                logger.error("Connection timed out")
                false
            }
        } catch (e: Exception) {
            logger.error("Connection failed: ${e.message}")
            false
        }
    }

    private fun waitForDisconnect() {
        // Wait for the connection to close or shutdown signal
        while (clientEndpoint.isConnected() && !shutdownRequested.get()) {
            Thread.sleep(1000)
        }
    }

    private fun printHelp() {
        println("Relay Tunnel Client v1.0.0")
        println()
        println("Usage: tunnel-client [options]")
        println()
        println("Options:")
        println("  -s, --server-url <url>    WebSocket server URL (env: RELAY_SERVER_URL)")
        println("  -k, --secret-key <key>    Authentication secret key (env: RELAY_SECRET_KEY)")
        println("  -l, --local-url <url>     Local application URL (env: RELAY_LOCAL_URL)")
        println("  -d, --subdomain <name>    Request specific subdomain (optional)")
        println("  -h, --help               Show this help message")
        println()
        println("Environment variables:")
        println("  RELAY_SERVER_URL         WebSocket server URL (default: ws://localhost:8080/ws)")
        println("  RELAY_SECRET_KEY         Authentication secret key")
        println("  RELAY_LOCAL_URL          Local application to proxy (default: http://localhost:3000)")
        println("  RELAY_SUBDOMAIN          Request specific subdomain (optional)")
    }
}

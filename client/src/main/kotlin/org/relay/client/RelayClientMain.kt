package org.relay.client

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.relay.client.command.ConnectionParameters
import org.relay.client.command.TunnelCommandInterface
import org.relay.client.command.TunnelCommandValidator
import org.relay.client.config.ClientConfig
import org.relay.client.retry.ReconnectionHandler
import org.relay.client.websocket.WebSocketClientEndpoint
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Main entry point for the Relay Tunnel Client.
 *
 * This class serves as the Quarkus Picocli @TopCommand that parses CLI arguments,
 * validates them, and configures the application before TunnelClient runs.
 *
 * The flow is:
 * 1. Quarkus starts and Picocli parses CLI args into this @TopCommand
 * 2. run() validates and builds ConnectionParameters
 * 3. System properties are set for ClientConfig
 * 4. TunnelClient (injected QuarkusApplication) is then started with configured settings
 */
@QuarkusMain()
@Command(
    name = "relay-client",
    mixinStandardHelpOptions = true,
    version = ["1.0.0"],
    description = ["Relay Tunnel Client - expose local HTTP services to the internet"],
    footer = [
        "",
        "Examples:",
        "  # Expose local service on port 3000 with random subdomain",
        "  relay-client 3000 -s tun.example.com -k my-secret",
        "",
        "  # Request specific subdomain 'myapp'",
        "  relay-client 8080 -s tun.example.com -d myapp -k secret-key",
        "",
        "  # Use insecure ws:// for local development",
        "  relay-client 3000 -s localhost:8080 -k test --insecure"
    ]
)
class RelayClientMain @Inject constructor(
    private val factory: IFactory,
    private val clientEndpoint: WebSocketClientEndpoint,
    private val reconnectionHandler: ReconnectionHandler
): Runnable, QuarkusApplication, TunnelCommandInterface {
    companion object {
        // Note: Argument parsing is now handled by TunnelCommand via RelayClientMain
        // System properties are set by TunnelCommand.call() before Quarkus starts

        // Exit code constants per spec
        private const val EXIT_CODE_INVALID_ARGS = 1
        private const val EXIT_CODE_CONNECTION_FAILED = 2
        private const val EXIT_CODE_AUTH_FAILED = 3

        // Exit code 130 = 128 + SIGINT(2) - standard Unix convention for interrupted process
        private const val EXIT_CODE_SIGINT = 130

        @JvmStatic
        fun main(args: Array<String>) {
            Quarkus.run(RelayClientMain::class.java, *args)
        }
    }

    @Parameters(index = "0", description = ["Local HTTP service port (1-65535)"], arity = "0..1")
    override var port: Int = 0

    @Option(names = ["-s", "--server"], description = ["Tunnel server hostname (e.g., tun.example.com)"], required = true)
    override var server: String = ""

    @Option(names = ["-p", "--server-port"], description = ["Tunnel server port (default: 443 for wss://, 80 for ws://)"])
    override var serverPort: Int? = null

    @Option(names = ["-k", "--key"], description = ["Authentication secret key"], required = true)
    override var secretKey: String = ""

    @Option(names = ["-d", "--subdomain"], description = ["Request specific subdomain (optional)"])
    override var subdomain: String? = null

    @Option(names = ["--insecure"], description = ["Use ws:// instead of wss:// (for local development)"])
    override var insecure: Boolean = false

    @Option(names = ["-q", "--quiet"], description = ["Suppress non-error output"])
    override var quiet: Boolean = false

    @Option(names = ["-v", "--verbose"], description = ["Enable debug logging"])
    override var verbose: Boolean = false

    override fun run() {
        if (verbose) {
            System.setProperty("quarkus.log.level", "DEBUG")
            System.setProperty("quarkus.log.category.\"org.relay\".level", "DEBUG")
        }

        // If help was requested (via mixinStandardHelpOptions), Picocli should have handled it
        // But in Quarkus integration, we need to check if port is 0 (not provided)
        // If port is 0 and required flags are missing, help was likely requested
        if (port == 0 && server.isBlank()) {
            // This means --help or similar was used, just return (Picocli already printed help)
            return
        }

        // Validate CLI arguments
        val validationErrors = TunnelCommandValidator.validate(port, server, secretKey, subdomain)

        if (validationErrors.isNotEmpty()) {
            validationErrors.forEach { System.err.println("Error: $it") }
            exitProcess(1)
        }



        val params = ConnectionParameters.fromCommand(this)

        // Configure system properties for ClientConfig
        // These MUST be set before Quarkus starts so ClientConfig can read them
        System.setProperty("relay.client.server-url", params.serverUrl)
        System.setProperty("relay.client.local-url", params.localUrl)
        System.setProperty("relay.client.secret-key", params.secretKey)
        params.subdomain?.let {
            System.setProperty("relay.client.subdomain", it)
        }

        // Configure logging based on verbosity flags
        if (quiet) {
            System.setProperty("quarkus.log.level", "ERROR")
        } else if (verbose) {
            System.setProperty("quarkus.log.level", "DEBUG")
        }

        // Start the tunnel client
        // Args are already parsed and system properties are set, so pass empty array
        exitProcess(runInternal(params))
    }

    override fun run(vararg args: String?): Int {
        return CommandLine(this, factory).execute(*args)
    }


    private val logger = LoggerFactory.getLogger(this::class.java)
    private val shutdownRequested = AtomicBoolean(false)
    private var exitCode = 0
    private val authenticationFailed = AtomicBoolean(false)


    fun onStart(@Observes event: StartupEvent) {
        // Startup event - application initialized
        // Configuration logging removed for cleaner CLI output
        // Use --verbose flag to see detailed connection info
    }

    fun onShutdown(@Observes event: ShutdownEvent) {
        // Clean shutdown - close WebSocket connection
        if (shutdownRequested.compareAndSet(false, true)) {
            clientEndpoint.close()
        }
    }

    fun runInternal(params: ConnectionParameters): Int {

        // Start connection loop
        while (!shutdownRequested.get()) {
            try {
                if (connect(params)) {
                    // Connection successful, wait for disconnect
                    waitForDisconnect()

                    if (shutdownRequested.get()) {
                        break
                    }

                    // Connection lost, check if we should reconnect
                    if (!reconnectionHandler.shouldReconnect()) {
                        logger.error("Connection lost and reconnection is disabled")
                        exitCode = EXIT_CODE_CONNECTION_FAILED
                        break
                    }

                    // Calculate and wait for next retry
                    val nextDelay = reconnectionHandler.calculateNextDelay()
                    logger.info("Reconnecting in ${nextDelay.seconds} seconds...")
                    reconnectionHandler.recordAttempt()

                    Thread.sleep(nextDelay.toMillis())
                } else {
                    // Connection failed - check if it's an authentication failure
                    if (authenticationFailed.get()) {
                        logger.error("Authentication failed - invalid secret key")
                        exitCode = EXIT_CODE_AUTH_FAILED
                        break
                    }

                    // Regular connection failure
                    if (!reconnectionHandler.shouldReconnect()) {
                        logger.error("Connection failed and reconnection is disabled")
                        exitCode = EXIT_CODE_CONNECTION_FAILED
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
                    exitCode = EXIT_CODE_CONNECTION_FAILED
                    break
                }
                Thread.sleep(1000)
            }
        }

        logger.info("Tunnel client shutting down with exit code $exitCode")
        return exitCode
    }


    private fun connect(params: ConnectionParameters): Boolean {
        logger.debug("Connecting to Relay server: localUrl={}, subdomain={}, insecure={}", 
            params.localUrl, params.subdomain ?: "auto", params.insecure)

        return try {
            logger.info("Connecting to ${params.serverUrl}...")

            // Append secret key as query parameter for authentication during handshake
            val baseUri = URI(params.serverUrl)
            val authUri = URI(
                baseUri.scheme,
                baseUri.authority,
                baseUri.path,
                "secret=${params.secretKey}",
                baseUri.fragment
            )

            val container = jakarta.websocket.ContainerProvider.getWebSocketContainer()

            container.connectToServer(clientEndpoint, authUri)

            // Wait for connection to be established (up to 10 seconds)
            var attempts = 0
            while (!clientEndpoint.isConnected() && attempts < 100) {
                Thread.sleep(100)
                attempts++
            }

            if (clientEndpoint.isConnected()) {
                logger.info("WebSocket connection established. Assigned URL: {}", clientEndpoint.publicUrl)
                reconnectionHandler.reset()
                true
            } else {
                logger.error("Connection timed out after 10 seconds")
                System.err.println("Failed to connect to ${params.serverUrl}: Connection timed out")
                false
            }
        } catch (e: Exception) {
            // Provide actionable error messages based on exception type
            val errorMessage = when {
                // TS-009: Authentication failure (TS-024: exit code 3)
                e.message?.contains("401") == true ||
                        e.message?.contains("Unauthorized") == true ||
                        e.message?.contains("Authentication failed") == true -> {
                    authenticationFailed.set(true)
                    "Authentication failed: Invalid secret key"
                }

                // TS-008: Connection refused / unreachable server (TS-023: exit code 2)
                e.message?.contains("Connection refused") == true ||
                        e.javaClass.simpleName == "ConnectException" -> {
                    "Failed to connect to ${params.serverUrl}: Connection refused. Check that the server is running and the URL is correct."
                }

                // Network unreachable (TS-023: exit code 2)
                e.message?.contains("Network is unreachable") == true -> {
                    "Failed to connect to ${params.serverUrl}: Network unreachable. Check your network connection."
                }

                // DNS resolution failure (TS-023: exit code 2)
                e.message?.contains("nodename nor servname provided") == true ||
                        e.message?.contains("Name or service not known") == true ||
                        e.javaClass.simpleName == "UnknownHostException" -> {
                    "Failed to connect to ${params.serverUrl}: Cannot resolve hostname. Check the server URL."
                }

                // SSL/TLS certificate errors (TS-023: exit code 2)
                e.message?.contains("certificate") == true ||
                        e.message?.contains("SSL") == true ||
                        e.message?.contains("TLS") == true ||
                        e.javaClass.simpleName.contains("SSL") -> {
                    "Failed to connect to ${params.serverUrl}: SSL certificate validation failed. Use --insecure flag if you trust this server."
                }

                // HTTP error codes
                e.message?.contains("HTTP response code") == true -> {
                    val httpCode = e.message?.let { msg ->
                        "\\d{3}".toRegex().find(msg)?.value
                    }
                    when (httpCode) {
                        "401", "403" -> {
                            // TS-024: Authentication failed - exit code 3
                            authenticationFailed.set(true)
                            "Authentication failed: Invalid secret key"
                        }

                        "404" -> "Failed to connect to ${params.serverUrl}: Server endpoint not found (404)"
                        "503" -> "Failed to connect to ${params.serverUrl}: Server unavailable (503)"
                        else -> "Failed to connect to ${params.serverUrl}: HTTP error ${httpCode ?: "unknown"}"
                    }
                }

                // Generic connection error (TS-023: exit code 2)
                else -> {
                    "Failed to connect to ${params.serverUrl}: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            logger.error(errorMessage)
            System.err.println(errorMessage)
            false
        }
    }

    private fun waitForDisconnect() {
        // Wait for the connection to close or shutdown signal
        while (clientEndpoint.isConnected() && !shutdownRequested.get()) {
            Thread.sleep(1000)
        }
    }

}

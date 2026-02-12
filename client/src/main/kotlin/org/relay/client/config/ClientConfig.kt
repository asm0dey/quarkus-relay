package org.relay.client.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/**
 * Configuration mapping for the Relay client.
 * Properties are prefixed with "relay.client" in the configuration file.
 */
@ConfigMapping(prefix = "relay.client")
interface ClientConfig {

    /**
     * WebSocket URL of the Relay server to connect to.
     * Example: wss://relay.example.com
     */
    fun serverUrl(): String

    /**
     * Secret key for authenticating with the Relay server.
     * This key must be pre-registered with the server.
     */
    fun secretKey(): Optional<String>

    /**
     * URL of the target application (local service) to forward requests to.
     * Example: http://localhost:8080
     */
    fun localUrl(): String

    /**
     * Requested subdomain for this client connection.
     * If not provided, the server will assign a random subdomain.
     */
    fun subdomain(): Optional<String>

    /**
     * Reconnection settings for automatic reconnection logic.
     * Properties are prefixed with "relay.client.reconnect".
     */
    fun reconnect(): ReconnectConfig

    /**
     * Configuration for reconnection behavior.
     */
    @ConfigMapping(prefix = "relay.client.reconnect")
    interface ReconnectConfig {

        /**
         * Whether automatic reconnection is enabled.
         * Default: true
         */
        @WithDefault("true")
        fun enabled(): Boolean

        /**
         * Initial delay before the first reconnection attempt.
         * Default: 1 second
         */
        @WithDefault("1s")
        fun initialDelay(): Duration

        /**
         * Maximum delay between reconnection attempts.
         * Default: 60 seconds
         */
        @WithDefault("60s")
        fun maxDelay(): Duration

        /**
         * Multiplier for exponential backoff.
         * Default: 2.0
         */
        @WithDefault("2.0")
        fun multiplier(): Double

        /**
         * Jitter factor for randomizing delay.
         * A value of 0.1 means ±5% variation from the calculated delay.
         * Default: 0.1
         */
        @WithDefault("0.1")
        fun jitter(): Double
    }
}

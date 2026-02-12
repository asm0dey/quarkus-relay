package org.relay.client.retry

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.relay.client.config.ClientConfig
import java.time.Duration
import kotlin.random.Random

/**
 * Service for managing reconnection logic with exponential backoff and jitter.
 * This service is application-scoped and handles the calculation of delays
 * between reconnection attempts to the Relay server.
 */
@ApplicationScoped
class ReconnectionHandler @Inject constructor(
    private val clientConfig: ClientConfig
) {

    /**
     * Current delay for the next reconnection attempt.
     * Starts at initialDelay and increases with each attempt.
     */
    @Volatile
    var currentDelay: Duration = clientConfig.reconnect().initialDelay()
        internal set

    /**
     * Counter for the number of reconnection attempts made.
     */
    @Volatile
    var attemptCount: Int = 0
        internal set

    /**
     * Random number generator for jitter calculation.
     */
    private val random = Random.Default

    /**
     * Calculates the delay for the next reconnection attempt using exponential backoff
     * with added jitter.
     *
     * The formula is: nextDelay = min(currentDelay * multiplier, maxDelay)
     * With jitter: delay * (1 + jitter * (random - 0.5))
     *
     * @return The calculated delay duration with jitter applied
     */
    fun calculateNextDelay(): Duration {
        val reconnectConfig = clientConfig.reconnect()

        // Calculate base delay with exponential backoff: currentDelay * multiplier
        val baseDelay = currentDelay.multipliedBy(reconnectConfig.multiplier().toLong())

        // Cap at maxDelay
        val cappedDelay = if (baseDelay > reconnectConfig.maxDelay()) {
            reconnectConfig.maxDelay()
        } else {
            baseDelay
        }

        // Apply jitter: delay * (1 + jitter * (random - 0.5))
        // This produces a value between delay * (1 - jitter/2) and delay * (1 + jitter/2)
        val jitterValue = reconnectConfig.jitter() * (random.nextDouble() - 0.5)
        val jitterMultiplier = 1.0 + jitterValue
        val jitteredMillis = (cappedDelay.toMillis() * jitterMultiplier).toLong()

        return Duration.ofMillis(jitteredMillis.coerceAtLeast(0))
    }

    /**
     * Records a reconnection attempt by incrementing the counter and updating
     * the current delay for the next attempt.
     */
    fun recordAttempt() {
        attemptCount++
        currentDelay = calculateNextDelay()
    }

    /**
     * Resets the reconnection handler to its initial state.
     * This should be called after a successful connection is established.
     */
    fun reset() {
        attemptCount = 0
        currentDelay = clientConfig.reconnect().initialDelay()
    }

    /**
     * Checks whether automatic reconnection is enabled based on configuration.
     *
     * @return true if reconnection is enabled, false otherwise
     */
    fun shouldReconnect(): Boolean {
        return clientConfig.reconnect().enabled()
    }
}

package org.relay.server.tunnel

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.relay.server.config.RelayConfig
import java.security.SecureRandom

/**
 * Service for generating random subdomains.
 * Uses [SecureRandom] for cryptographically secure randomness.
 *
 * @property relayConfig The relay configuration for length settings
 */
@ApplicationScoped
class SubdomainGenerator @Inject constructor(
    private val relayConfig: RelayConfig
) {
    private val random = SecureRandom()
    private val alphanumericChars = "abcdefghijklmnopqrstuvwxyz0123456789"

    companion object {
        private const val DEFAULT_SUBDOMAIN_LENGTH = 12
    }

    /**
     * Generates a random alphanumeric subdomain of the configured length.
     * The generated subdomain contains only lowercase letters and digits.
     *
     * @return A randomly generated subdomain string
     */
    fun generate(): String {
        val length = relayConfig.subdomainLength()
        return generate(length)
    }

    /**
     * Generates a random alphanumeric subdomain of the specified length.
     * The generated subdomain contains only lowercase letters and digits.
     *
     * @param length The length of the subdomain to generate
     * @return A randomly generated subdomain string
     */
    fun generate(length: Int): String {
        require(length > 0) { "Subdomain length must be positive" }
        
        return buildString(length) {
            repeat(length) {
                append(alphanumericChars[random.nextInt(alphanumericChars.length)])
            }
        }
    }

    /**
     * Generates a unique subdomain that doesn't collide with existing tunnels.
     * Will regenerate if a collision is detected, up to a maximum number of attempts.
     *
     * @param registry The tunnel registry to check for collisions
     * @param maxAttempts Maximum number of generation attempts (default 100)
     * @return A unique subdomain string
     * @throws IllegalStateException if unable to generate a unique subdomain after max attempts
     */
    fun generateUnique(registry: TunnelRegistry, maxAttempts: Int = 100): String {
        repeat(maxAttempts) {
            val subdomain = generate()
            if (!registry.hasTunnel(subdomain)) {
                return subdomain
            }
        }
        throw IllegalStateException(
            "Unable to generate unique subdomain after $maxAttempts attempts"
        )
    }

    /**
     * Generates a unique subdomain with a specific length.
     *
     * @param registry The tunnel registry to check for collisions
     * @param length The length of the subdomain to generate
     * @param maxAttempts Maximum number of generation attempts (default 100)
     * @return A unique subdomain string
     * @throws IllegalStateException if unable to generate a unique subdomain after max attempts
     */
    fun generateUnique(registry: TunnelRegistry, length: Int, maxAttempts: Int = 100): String {
        repeat(maxAttempts) {
            val subdomain = generate(length)
            if (!registry.hasTunnel(subdomain)) {
                return subdomain
            }
        }
        throw IllegalStateException(
            "Unable to generate unique subdomain after $maxAttempts attempts"
        )
    }
}

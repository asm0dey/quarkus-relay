package org.relay.client.retry

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.relay.client.config.ClientConfig
import java.time.Duration

class ReconnectionHandlerTest {

    private lateinit var clientConfig: ClientConfig
    private lateinit var reconnectConfig: ClientConfig.ReconnectConfig
    private lateinit var reconnectionHandler: ReconnectionHandler

    @BeforeEach
    fun setup() {
        reconnectConfig = mock {
            on { initialDelay() } doReturn Duration.ofSeconds(1)
            on { maxDelay() } doReturn Duration.ofSeconds(60)
            on { multiplier() } doReturn 2.0
            on { jitter() } doReturn 0.0 // No jitter for deterministic testing
        }
        clientConfig = mock {
            on { reconnect() } doReturn reconnectConfig
        }
        
        reconnectionHandler = ReconnectionHandler(clientConfig)
    }

    @Test
    fun `initial delay follows configuration`() {
        assertEquals(Duration.ofSeconds(1), reconnectionHandler.currentDelay)
    }

    @Test
    fun `exponential backoff doubles delay`() {
        reconnectionHandler.recordAttempt()
        assertEquals(Duration.ofSeconds(2), reconnectionHandler.currentDelay)
        
        reconnectionHandler.recordAttempt()
        assertEquals(Duration.ofSeconds(4), reconnectionHandler.currentDelay)
        
        reconnectionHandler.recordAttempt()
        assertEquals(Duration.ofSeconds(8), reconnectionHandler.currentDelay)
    }

    @Test
    fun `delay is capped at maxDelay`() {
        // Force initial delay to 40s
        reconnectionHandler.currentDelay = Duration.ofSeconds(40)
        
        reconnectionHandler.recordAttempt()
        assertEquals(Duration.ofSeconds(60), reconnectionHandler.currentDelay)
    }

    @Test
    fun `reset restores initial delay`() {
        reconnectionHandler.recordAttempt()
        reconnectionHandler.recordAttempt()
        assertTrue(reconnectionHandler.currentDelay > Duration.ofSeconds(1))
        assertTrue(reconnectionHandler.attemptCount > 0)
        
        reconnectionHandler.reset()
        assertEquals(Duration.ofSeconds(1), reconnectionHandler.currentDelay)
        assertEquals(0, reconnectionHandler.attemptCount)
    }

    @Test
    fun `jitter applies randomized variation`() {
        // Use a new mock with jitter
        val reconnectWithJitter = mock<ClientConfig.ReconnectConfig> {
            on { initialDelay() } doReturn Duration.ofSeconds(1)
            on { maxDelay() } doReturn Duration.ofSeconds(60)
            on { multiplier() } doReturn 2.0
            on { jitter() } doReturn 0.2
        }
        val configWithJitter = mock<ClientConfig> {
            on { reconnect() } doReturn reconnectWithJitter
        }
        val handlerWithJitter = ReconnectionHandler(configWithJitter)
        
        val baseDelay = Duration.ofSeconds(2) // 1s * 2
        val minJittered = baseDelay.toMillis() * (1.0 - 0.1)
        val maxJittered = baseDelay.toMillis() * (1.0 + 0.1)
        
        val delays = (1..100).map { handlerWithJitter.calculateNextDelay().toMillis() }
        
        delays.forEach { delay ->
            assertTrue(delay >= minJittered.toLong(), "Delay $delay too small")
            assertTrue(delay <= maxJittered.toLong(), "Delay $delay too large")
        }
        
        // Ensure there's some variation
        assertTrue(delays.distinct().size > 1)
    }
}

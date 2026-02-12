package org.relay.client.config

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import jakarta.inject.Inject

@QuarkusTest
@TestProfile(ClientConfigTest.TestProfile::class)
class ClientConfigTest {

    @Inject
    lateinit var clientConfig: ClientConfig

    class TestProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            return mapOf(
                "relay.client.server-url" to "wss://test.relay.example.com",
                "relay.client.secret-key" to "test-secret-key-12345",
                "relay.client.local-url" to "http://localhost:9090",
                "relay.client.subdomain" to "my-custom-subdomain",
                "relay.client.reconnect.enabled" to "true",
                "relay.client.reconnect.initial-delay" to "2s",
                "relay.client.reconnect.max-delay" to "30s",
                "relay.client.reconnect.multiplier" to "1.5",
                "relay.client.reconnect.jitter" to "0.2"
            )
        }
    }

    @Test
    fun `Config parsing - parses server URL from application config`() {
        assertEquals("wss://test.relay.example.com", clientConfig.serverUrl())
    }

    @Test
    fun `Config parsing - parses secret key from application config`() {
        assertTrue(clientConfig.secretKey().isPresent)
        assertEquals("test-secret-key-12345", clientConfig.secretKey().get())
    }

    @Test
    fun `Config parsing - parses local URL from application config`() {
        assertEquals("http://localhost:9090", clientConfig.localUrl())
    }

    @Test
    fun `Config parsing - parses subdomain from application config`() {
        assertTrue(clientConfig.subdomain().isPresent)
        assertEquals("my-custom-subdomain", clientConfig.subdomain().get())
    }

    @Test
    fun `Config parsing - parses reconnect enabled from application config`() {
        assertTrue(clientConfig.reconnect().enabled())
    }

    @Test
    fun `Config parsing - parses initial delay from application config`() {
        assertEquals(Duration.ofSeconds(2), clientConfig.reconnect().initialDelay())
    }

    @Test
    fun `Config parsing - parses max delay from application config`() {
        assertEquals(Duration.ofSeconds(30), clientConfig.reconnect().maxDelay())
    }

    @Test
    fun `Config parsing - parses multiplier from application config`() {
        assertEquals(1.5, clientConfig.reconnect().multiplier())
    }

    @Test
    fun `Config parsing - parses jitter from application config`() {
        assertEquals(0.2, clientConfig.reconnect().jitter())
    }

    @Test
    fun `Config parsing - reconnect config is accessible`() {
        val reconnectConfig = clientConfig.reconnect()
        assertNotNull(reconnectConfig)
        assertTrue(reconnectConfig.enabled())
    }
}

@QuarkusTest
@TestProfile(ClientConfigDefaultValuesTest.DefaultValuesProfile::class)
class ClientConfigDefaultValuesTest {

    @Inject
    lateinit var clientConfig: ClientConfig

    class DefaultValuesProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            return mapOf(
                "relay.client.server-url" to "wss://test.relay.example.com",
                "relay.client.secret-key" to "test-secret-key",
                "relay.client.local-url" to "http://localhost:8080",
                "relay.client.reconnect.enabled" to "true",
                "relay.client.reconnect.initial-delay" to "1s",
                "relay.client.reconnect.max-delay" to "60s",
                "relay.client.reconnect.multiplier" to "2.0",
                "relay.client.reconnect.jitter" to "0.1"
                // Note: subdomain not specified - should be empty optional
            )
        }
    }

    @Test
    fun `Default values - reconnect enabled defaults to true`() {
        assertTrue(clientConfig.reconnect().enabled())
    }

    @Test
    fun `Default values - initial delay defaults to 1 second`() {
        assertEquals(Duration.ofSeconds(1), clientConfig.reconnect().initialDelay())
    }

    @Test
    fun `Default values - max delay defaults to 60 seconds`() {
        assertEquals(Duration.ofSeconds(60), clientConfig.reconnect().maxDelay())
    }

    @Test
    fun `Default values - multiplier defaults to 2_0`() {
        assertEquals(2.0, clientConfig.reconnect().multiplier())
    }

    @Test
    fun `Default values - jitter defaults to 0_1`() {
        assertEquals(0.1, clientConfig.reconnect().jitter())
    }
}

@QuarkusTest
@TestProfile(ClientConfigOptionalSubdomainTest.OptionalSubdomainProfile::class)
class ClientConfigOptionalSubdomainTest {

    @Inject
    lateinit var clientConfig: ClientConfig

    class OptionalSubdomainProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> {
            return mapOf(
                "relay.client.server-url" to "wss://test.relay.example.com",
                "relay.client.secret-key" to "test-secret-key",
                "relay.client.local-url" to "http://localhost:8080",
                "relay.client.reconnect.enabled" to "true",
                "relay.client.reconnect.initial-delay" to "1s",
                "relay.client.reconnect.max-delay" to "60s",
                "relay.client.reconnect.multiplier" to "2.0",
                "relay.client.reconnect.jitter" to "0.1"
                // Note: subdomain not specified - should be empty optional
            )
        }
    }

    @Test
    fun `Optional subdomain - subdomain is empty when not provided`() {
        val subdomain = clientConfig.subdomain()
        
        assertFalse(subdomain.isPresent, "Subdomain should be empty when not configured")
    }

    @Test
    fun `Optional subdomain - can access other config without subdomain`() {
        assertEquals("wss://test.relay.example.com", clientConfig.serverUrl())
        assertTrue(clientConfig.secretKey().isPresent)
        assertEquals("test-secret-key", clientConfig.secretKey().get())
        assertEquals("http://localhost:8080", clientConfig.localUrl())
    }
}

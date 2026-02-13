package org.relay.server.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.websocket.*
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.relay.server.tunnel.TunnelRegistry
import org.relay.shared.protocol.Envelope
import org.relay.shared.protocol.MessageType
import java.net.URI
import java.net.URL
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@QuarkusTest
@TestProfile(IntegrationTestProfile::class)
class AuthenticationIntegrationTest {

    @Inject
    lateinit var tunnelRegistry: TunnelRegistry

    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
    private val sessions = mutableListOf<Session>()

    companion object {
        const val VALID_KEY = "test-secret-key"
        const val INVALID_KEY = "invalid-secret-key"
    }

    @BeforeEach
    fun setup() {
        tunnelRegistry.clear()
        sessions.clear()
    }

    @AfterEach
    fun tearDown() {
        sessions.forEach { it.close() }
        sessions.clear()
        tunnelRegistry.clear()
    }

    @Test
    fun `TS-001 Valid secret key connection receives subdomain`() {
        val (_, client) = connectWebSocket(VALID_KEY)

        awaitMessage(client)
        
        val json = client.messages.first()
        println("[DEBUG_LOG] Received message: $json")
        val envelope = parseEnvelope(json)
        assertNotNull(envelope, "Envelope should not be null for message: $json")
        assertEquals(MessageType.CONTROL, envelope!!.type)
        
        val payload = envelope.payload
        assertEquals("REGISTERED", payload.get("action").asText())
        val subdomain = payload.get("subdomain").asText()
        assertTrue(subdomain.isNotBlank())
        
        // Verify tunnel is registered
        await().atMost(Duration.ofSeconds(2))
            .until { tunnelRegistry.hasTunnel(subdomain) }
    }

    @Test
    fun `TS-002 Invalid secret key rejected with connection close`() {
        val (_, client) = connectWebSocket(INVALID_KEY)
        
        await().atMost(Duration.ofSeconds(5))
            .until { client.closed }
        
        assertNotNull(client.closeReason)
        assertEquals(1008, client.closeReason!!.closeCode.code)
    }

    @Test
    fun `TS-003 Multiple connections with same key create independent tunnels`() {
        val subdomains = mutableSetOf<String>()
        
        repeat(3) {
            val (_, client) = connectWebSocket(VALID_KEY)
            awaitMessage(client)
            
            val subdomain = extractSubdomain(client.messages.first())
            assertNotNull(subdomain)
            subdomains.add(subdomain!!)
        }
        
        assertEquals(3, subdomains.size)
        assertEquals(3, tunnelRegistry.size())
    }

    @TestHTTPResource
    var baseUrl: URL? = null

    private fun connectWebSocket(secretKey: String): Pair<Session, TestWsClient> {
        val container = ContainerProvider.getWebSocketContainer()
        val client = TestWsClient()
        val session = container.connectToServer(
            client, URI("ws://localhost:${baseUrl!!.port}/ws?secret=$secretKey")
        )
        sessions.add(session)
        return session to client
    }

    private fun awaitMessage(client: TestWsClient) {
        await().atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(100))
            .until { client.messages.isNotEmpty() }
    }

    private fun parseEnvelope(json: String): Envelope? {
        return try {
            mapper.readValue(json, Envelope::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSubdomain(json: String): String? {
        val envelope = parseEnvelope(json) ?: return null
        if (envelope.type != MessageType.CONTROL) return null
        return envelope.payload.get("subdomain")?.asText()
    }

    @ClientEndpoint
    class TestWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        @Volatile var closeReason: CloseReason? = null

        @OnMessage
        fun onMessage(message: String) { messages.add(message) }

        @OnClose
        fun onClose(session: Session, reason: CloseReason) {
            closed = true
            closeReason = reason
        }
    }
}

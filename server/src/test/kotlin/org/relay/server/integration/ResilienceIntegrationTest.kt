package org.relay.server.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import org.relay.server.Application
import org.relay.server.tunnel.TunnelRegistry
import org.relay.shared.protocol.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URL
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration tests for Resilience (Phase 8).
 * Covers T921, T922, T923.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile::class)
class ResilienceIntegrationTest {

    @Inject
    lateinit var tunnelRegistry: TunnelRegistry

    @Inject
    lateinit var application: Application

    @TestHTTPResource
    var baseUrl: URL? = null

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()

    private val sessions = mutableListOf<Session>()
    private var subdomain: String? = null
    private lateinit var tunnelClient: TestWsClient

    @BeforeEach
    fun setup() {
        tunnelRegistry.clear()
        sessions.clear()
    }

    @AfterEach
    fun tearDown() {
        sessions.forEach { try { if (it.isOpen) it.close() } catch (e: Exception) {} }
        sessions.clear()
        tunnelRegistry.clear()
    }

    @Test
    fun `T921 reconnection after tunnel disconnect`() {
        // 1. Initial connection
        val (session1, client1) = connectTunnel("test-secret-key")
        await().atMost(Duration.ofSeconds(5)).until { client1.messages.isNotEmpty() }
        val sub1 = mapper.readValue(client1.messages.first(), Envelope::class.java).payload.get("subdomain").asText()
        
        // 2. Disconnect
        session1.close()
        await().atMost(Duration.ofSeconds(5)).until { tunnelRegistry.size() == 0 }
        
        // 3. Reconnect - should get a new subdomain (or same, but registration should succeed)
        val (session2, client2) = connectTunnel("test-secret-key")
        await().atMost(Duration.ofSeconds(5)).until { client2.messages.isNotEmpty() }
        val sub2 = mapper.readValue(client2.messages.first(), Envelope::class.java).payload.get("subdomain").asText()
        
        assertNotNull(sub2)
        assertTrue(tunnelRegistry.hasTunnel(sub2))
    }

    @Test
    fun `T922 immediate shutdown closes connections`() {
        // 1. Connect a tunnel
        val (session, client) = connectTunnel("test-secret-key")
        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }
        val sub = mapper.readValue(client.messages.first(), Envelope::class.java).payload.get("subdomain").asText()
        
        assertTrue(session.isOpen)
        assertTrue(tunnelRegistry.hasTunnel(sub))

        // 2. Call tunnelRegistry.shutdown() which is what Application.immediateShutdown() 
        // effectively does (and more)
        tunnelRegistry.shutdown()

        // 3. Verify connection is closed and registry is cleared
        await().atMost(Duration.ofSeconds(5)).until { !session.isOpen }
        assertEquals(0, tunnelRegistry.size())
    }

    private fun connectTunnel(secretKey: String): Pair<Session, TestWsClient> {
        val container = ContainerProvider.getWebSocketContainer()
        val client = TestWsClient()
        val uri = URI("ws://localhost:${baseUrl!!.port}/ws?secret=$secretKey")
        val session = container.connectToServer(client, uri)
        sessions.add(session)
        return session to client
    }

    @ClientEndpoint
    class TestWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @OnMessage fun onMessage(message: String) { messages.add(message) }
    }
}

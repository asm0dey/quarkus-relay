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
import org.relay.server.tunnel.TunnelRegistry
import org.relay.shared.protocol.*
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration tests for Edge Cases (Phase 9).
 * Covers T1011, T1012, T1013.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile::class)
class EdgeCaseIntegrationTest {

    @Inject
    lateinit var tunnelRegistry: TunnelRegistry

    @TestHTTPResource
    var baseUrl: URL? = null

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private val sessions = mutableListOf<Session>()
    private var subdomain: String? = null
    private lateinit var tunnelClient: TestWsClient

    @BeforeEach
    fun setup() {
        tunnelRegistry.clear()
        sessions.clear()
        
        // Connect tunnel
        val container = ContainerProvider.getWebSocketContainer()
        tunnelClient = TestWsClient()
        val session = container.connectToServer(tunnelClient, URI("ws://localhost:${baseUrl!!.port}/ws?secret=test-secret-key"))
        sessions.add(session)
        
        await().atMost(Duration.ofSeconds(5)).until { tunnelClient.messages.isNotEmpty() }
        subdomain = mapper.readValue(tunnelClient.messages.first(), Envelope::class.java).payload.get("subdomain").asText()
        tunnelClient.messages.clear()
    }

    @AfterEach
    fun tearDown() {
        sessions.forEach { try { if (it.isOpen) it.close() } catch (e: Exception) {} }
        sessions.clear()
        tunnelRegistry.clear()
    }

    @Test
    fun `T1011 body size limit 10MB`() {
        // Create 11MB body
        val largeBody = "a".repeat(11 * 1024 * 1024)
        
        val request = HttpRequest.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .uri(URI("http://localhost:${baseUrl!!.port}/post"))
            .header("X-Relay-Subdomain", subdomain!!)
            .POST(HttpRequest.BodyPublishers.ofString(largeBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(413, response.statusCode())
    }

    @Test
    fun `T1012 non-HTTP response from local app`() {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:${baseUrl!!.port}/error"))
            .header("X-Relay-Subdomain", subdomain!!)
            .GET()
            .build()

        val responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        // Tunnel client receives request
        await().atMost(Duration.ofSeconds(5)).until { tunnelClient.messages.isNotEmpty() }
        val requestEnvelope = mapper.readValue(tunnelClient.messages.first(), Envelope::class.java)
        
        // Send back ERROR instead of RESPONSE
        val errorPayload = ErrorPayload(ErrorCode.UPSTREAM_ERROR, "Non-HTTP response")
        val errorEnvelope = Envelope(
            correlationId = requestEnvelope.correlationId,
            type = MessageType.ERROR,
            payload = mapper.valueToTree(errorPayload)
        )
        sessions.first().basicRemote.sendText(mapper.writeValueAsString(errorEnvelope))

        val response = responseFuture.get()
        assertEquals(502, response.statusCode())
    }

    @ClientEndpoint
    class TestWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @OnMessage fun onMessage(message: String) { messages.add(message) }
    }
}

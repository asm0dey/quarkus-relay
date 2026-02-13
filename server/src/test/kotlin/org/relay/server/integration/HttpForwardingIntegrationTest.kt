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
 * Integration tests for HTTP forwarding (Phase 4).
 * Covers T521, T522, T524, T525.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile::class)
class HttpForwardingIntegrationTest {

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
    private lateinit var client: TestWsClient

    companion object {
        const val VALID_KEY = "test-secret-key"
    }

    @BeforeEach
    fun setup() {
        tunnelRegistry.clear()
        sessions.clear()
        
        // Connect and get subdomain
        val (session, wsClient) = connectWebSocket(VALID_KEY)
        client = wsClient
        
        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }
        val envelope = mapper.readValue(client.messages.first(), Envelope::class.java)
        subdomain = envelope.payload.get("subdomain").asText()
        
        // Clear registration message for clean test state
        client.messages.clear()
    }

    @AfterEach
    fun tearDown() {
        sessions.forEach { it.close() }
        sessions.clear()
        tunnelRegistry.clear()
    }

    @Test
    fun `T521 HTTP GET forwarding end-to-end`() {
        // 1. Send HTTP request to server with X-Relay-Subdomain header
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:${baseUrl!!.port}/api/test?foo=bar"))
            .header("X-Relay-Subdomain", subdomain!!)
            .GET()
            .build()

        val responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        // 2. Client should receive REQUEST message
        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }
        val requestEnvelope = mapper.readValue(client.messages.first(), Envelope::class.java)
        assertEquals(MessageType.REQUEST, requestEnvelope.type)
        
        val requestPayload = mapper.treeToValue(requestEnvelope.payload, RequestPayload::class.java)
        assertEquals("GET", requestPayload.method)
        assertEquals("/api/test", requestPayload.path)
        assertEquals("bar", requestPayload.query?.get("foo"))

        // 3. Client sends back RESPONSE message
        val responsePayload = ResponsePayload(
            statusCode = 200,
            headers = mapOf("Content-Type" to "text/plain"),
            body = "Hello from tunnel"
        )
        val responseEnvelope = Envelope(
            correlationId = requestEnvelope.correlationId,
            type = MessageType.RESPONSE,
            payload = mapper.valueToTree(responsePayload)
        )
        
        sessions.first().basicRemote.sendText(mapper.writeValueAsString(responseEnvelope))

        // 4. Server should return the response to the original HTTP requester
        val response = responseFuture.get()
        assertEquals(200, response.statusCode())
        assertEquals("Hello from tunnel", response.body())
        assertEquals("text/plain", response.headers().firstValue("Content-Type").get())
    }

    @Test
    fun `T522 POST with body forwarding`() {
        val requestBody = "{\"key\":\"value\"}"
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:${baseUrl!!.port}/post"))
            .header("X-Relay-Subdomain", subdomain!!)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }
        val requestEnvelope = mapper.readValue(client.messages.first(), Envelope::class.java)
        val requestPayload = mapper.treeToValue(requestEnvelope.payload, RequestPayload::class.java)
        
        assertEquals("POST", requestPayload.method)
        assertEquals(requestBody, requestPayload.body)

        val responsePayload = ResponsePayload(201, emptyMap(), "Created")
        val responseEnvelope = Envelope(
            correlationId = requestEnvelope.correlationId,
            type = MessageType.RESPONSE,
            payload = mapper.valueToTree(responsePayload)
        )
        sessions.first().basicRemote.sendText(mapper.writeValueAsString(responseEnvelope))

        val response = responseFuture.get()
        assertEquals(201, response.statusCode())
    }

    @Test
    fun `T524 request timeout returns 504`() {
        // This relies on relay.request-timeout being small in IntegrationTestProfile (set to 5s)
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:${baseUrl!!.port}/slow"))
            .header("X-Relay-Subdomain", subdomain!!)
            .GET()
            .build()

        val responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        // Client receives request but doesn't respond
        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }

        // Wait for timeout (IntegrationTestProfile says 5s, so we wait 7s)
        val response = responseFuture.get()
        assertEquals(504, response.statusCode())
    }

    @Test
    fun `T525 client disconnect during request returns 503`() {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:${baseUrl!!.port}/disconnect"))
            .header("X-Relay-Subdomain", subdomain!!)
            .GET()
            .build()

        val responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        await().atMost(Duration.ofSeconds(5)).until { client.messages.isNotEmpty() }
        
        // Close client session abruptly
        sessions.first().close()

        val response = responseFuture.get()
        assertEquals(503, response.statusCode())
    }

    private fun connectWebSocket(secretKey: String): Pair<Session, TestWsClient> {
        val container = ContainerProvider.getWebSocketContainer()
        val client = TestWsClient()
        val session = container.connectToServer(
            client, URI("ws://localhost:${baseUrl!!.port}/ws?secret=$secretKey")
        )
        sessions.add(session)
        return session to client
    }

    @ClientEndpoint
    class TestWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @Volatile var closed = false

        @OnMessage
        fun onMessage(message: String) { messages.add(message) }

        @OnClose
        fun onClose(session: Session, reason: CloseReason) {
            closed = true
        }
    }
}

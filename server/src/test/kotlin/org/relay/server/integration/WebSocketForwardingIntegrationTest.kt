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
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URL
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration tests for WebSocket forwarding (Phase 7).
 * Covers T821, T822, T823, T824.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile::class)
class WebSocketForwardingIntegrationTest {
    @TestHTTPResource
    var baseUrl: URL? = null

    @Inject
    lateinit var tunnelRegistry: TunnelRegistry

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()

    private val sessions = mutableListOf<Session>()
    private var subdomain: String? = null
    private lateinit var tunnelClient: TestTunnelWsClient

    @BeforeEach
    fun setup() {
        tunnelRegistry.clear()
        sessions.clear()
        
        // Connect tunnel client
        val (_, wsClient) = connectTunnelWebSocket("test-secret-key")
        tunnelClient = wsClient
        
        await().atMost(Duration.ofSeconds(10)).until { tunnelClient.messages.isNotEmpty() }
        val envelope = mapper.readValue(tunnelClient.messages.first(), Envelope::class.java)
        subdomain = envelope.payload.get("subdomain").asText()
        tunnelClient.messages.clear()
        
        logger.info("Tunnel established for subdomain: {}", subdomain)
    }

    @AfterEach
    fun tearDown() {
        logger.info("Tearing down test...")
        sessions.forEach { 
            try {
                if (it.isOpen) it.close()
            } catch (_: Exception) {}
        }
        sessions.clear()
        tunnelRegistry.clear()
    }

    @Test
    fun `T821 WebSocket upgrade and message forwarding`() {
        logger.info("Starting T821 test...")
        // 1. External client connects to public WebSocket endpoint
        val externalClient = TestExternalWsClient()
        val externalUri = URI("ws://localhost:${baseUrl!!.port}/pub?X-Relay-Subdomain=$subdomain")
        logger.info("External client connecting to: {}", externalUri)
        
        val externalSession = try {
            var s: Session? = null
            var lastEx: Exception? = null
            for (i in 1..5) {
                try {
                    s = ContainerProvider.getWebSocketContainer().connectToServer(externalClient, externalUri)
                    break
                } catch (e: Exception) {
                    lastEx = e
                    logger.warn("External connection attempt $i failed: ${e.message}")
                    Thread.sleep(1000)
                }
            }
            s ?: throw lastEx!!
        } catch (e: Exception) {
            logger.error("Failed to connect external client", e)
            throw e
        }
        sessions.add(externalSession)

        // 2. Tunnel client should receive Upgrade REQUEST
        logger.info("Waiting for upgrade request on tunnel client...")
        await().atMost(Duration.ofSeconds(10)).until { tunnelClient.messages.isNotEmpty() }
        val upgradeEnvelope = mapper.readValue(tunnelClient.messages.first(), Envelope::class.java)
        assertEquals(MessageType.REQUEST, upgradeEnvelope.type)
        
        val requestPayload = mapper.treeToValue(upgradeEnvelope.payload, RequestPayload::class.java)
        assertTrue(requestPayload.webSocketUpgrade)
        
        // 3. Tunnel client sends back RESPONSE (Upgrade success)
        logger.info("Sending upgrade success response from tunnel client...")
        val responsePayload = ResponsePayload(101, emptyMap(), null)
        val responseEnvelope = Envelope(
            correlationId = upgradeEnvelope.correlationId,
            type = MessageType.RESPONSE,
            payload = mapper.valueToTree(responsePayload)
        )
        val tunnelSession = sessions.first { it.requestURI.path.endsWith("/ws") }
        tunnelSession.basicRemote.sendText(mapper.writeValueAsString(responseEnvelope))
        
        tunnelClient.messages.clear()

        // 4. T822 External client sends message -> Tunnel client receives it
        logger.info("Sending text message from external client...")
        val testMessage = "Hello from external"
        externalSession.basicRemote.sendText(testMessage)
        
        await().atMost(Duration.ofSeconds(10)).until { tunnelClient.messages.isNotEmpty() }
        val frameEnvelope = mapper.readValue(tunnelClient.messages.first(), Envelope::class.java)
        val framePayload = mapper.treeToValue(frameEnvelope.payload, WebSocketFramePayload::class.java)
        assertEquals(WebSocketFramePayload.TYPE_TEXT, framePayload.type)
        assertEquals(testMessage, framePayload.data)
        
        tunnelClient.messages.clear()

        // 5. T823 Tunnel client sends message -> External client receives it
        logger.info("Sending reply frame from tunnel client...")
        val replyMessage = "Reply from tunnel"
        val replyFrame = WebSocketFramePayload(WebSocketFramePayload.TYPE_TEXT, replyMessage)
        val replyEnvelope = Envelope(
            correlationId = upgradeEnvelope.correlationId,
            type = MessageType.RESPONSE,
            payload = mapper.valueToTree(replyFrame)
        )
        tunnelSession.basicRemote.sendText(mapper.writeValueAsString(replyEnvelope))
        
        await().atMost(Duration.ofSeconds(10)).until { externalClient.messages.isNotEmpty() }
        assertEquals(replyMessage, externalClient.messages.first())
        logger.info("T821 test passed!")
    }

    private fun connectTunnelWebSocket(secretKey: String): Pair<Session, TestTunnelWsClient> {
        val container = ContainerProvider.getWebSocketContainer()
        val client = TestTunnelWsClient()
        val uri = URI("ws://localhost:${baseUrl!!.port}/ws?secret=$secretKey")
        logger.info("Tunnel client connecting to: {}", uri)
        
        var attempt = 0
        var session: Session? = null
        while (attempt < 5) {
            try {
                session = container.connectToServer(client, uri)
                break
            } catch (e: Exception) {
                attempt++
                logger.warn("Connection attempt $attempt failed: ${e.message}", e)
                Thread.sleep(1000)
            }
        }
        if (session == null) throw RuntimeException("Failed to connect tunnel client after $attempt attempts")
        
        sessions.add(session)
        return session to client
    }

    @ClientEndpoint
    class TestTunnelWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @OnMessage fun onMessage(message: String) { 
            LoggerFactory.getLogger(TestTunnelWsClient::class.java).info("Tunnel client received message: {}", message)
            messages.add(message) 
        }
    }

    @ClientEndpoint
    class TestExternalWsClient {
        val messages = CopyOnWriteArrayList<String>()
        @OnMessage fun onMessage(message: String) { 
            LoggerFactory.getLogger(TestExternalWsClient::class.java).info("External client received message: {}", message)
            messages.add(message) 
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebSocketForwardingIntegrationTest::class.java)
    }
}

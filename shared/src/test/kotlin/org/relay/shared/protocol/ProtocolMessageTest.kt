package org.relay.shared.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class ProtocolMessageTest {

    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setup() {
        objectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    @Test
    fun `MessageType serialization - all enum values serialize correctly to strings`() {
        // Test that each MessageType serializes to its string value
        MessageType.entries.forEach { messageType ->
            val json = objectMapper.writeValueAsString(messageType)
            // Should serialize as the string value, not the enum name
            assertEquals("\"${messageType.type}\"", json)
        }
    }

    @Test
    fun `MessageType deserialization - all string values deserialize correctly`() {
        // Test that each string value deserializes to the correct MessageType
        MessageType.entries.forEach { messageType ->
            val json = "\"${messageType.type}\""
            val deserialized = objectMapper.readValue(json, MessageType::class.java)
            assertEquals(messageType, deserialized)
        }
    }

    @Test
    fun `MessageType fromString - finds correct enum values`() {
        assertEquals(MessageType.REQUEST, MessageType.fromString("REQUEST"))
        assertEquals(MessageType.RESPONSE, MessageType.fromString("RESPONSE"))
        assertEquals(MessageType.ERROR, MessageType.fromString("ERROR"))
        assertEquals(MessageType.CONTROL, MessageType.fromString("CONTROL"))
        assertNull(MessageType.fromString("UNKNOWN"))
    }

    @Test
    fun `Envelope serialization - round-trip JSON serialization and deserialization`() {
        val payload = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            mapOf("key" to "value", "number" to 42)
        )
        val envelope = Envelope(
            correlationId = "test-correlation-123",
            type = MessageType.REQUEST,
            timestamp = Instant.parse("2024-01-15T10:30:00Z"),
            payload = payload
        )

        val json = objectMapper.writeValueAsString(envelope)
        val deserialized = objectMapper.readValue(json, Envelope::class.java)

        assertEquals(envelope.correlationId, deserialized.correlationId)
        assertEquals(envelope.type, deserialized.type)
        assertEquals(envelope.timestamp, deserialized.timestamp)
        assertEquals(envelope.payload, deserialized.payload)
    }

    @Test
    fun `RequestPayload serialization - serializes correctly including base64 body`() {
        val originalBody = "Hello, World!"
        val base64Body = Base64.getEncoder().encodeToString(originalBody.toByteArray())
        
        val requestPayload = RequestPayload(
            method = "POST",
            path = "/api/test",
            query = mapOf("param1" to "value1", "param2" to "value2"),
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer token123"),
            body = base64Body
        )

        val json = objectMapper.writeValueAsString(requestPayload)
        val deserialized = objectMapper.readValue(json, RequestPayload::class.java)

        assertEquals(requestPayload.method, deserialized.method)
        assertEquals(requestPayload.path, deserialized.path)
        assertEquals(requestPayload.query, deserialized.query)
        assertEquals(requestPayload.headers, deserialized.headers)
        assertEquals(requestPayload.body, deserialized.body)
        
        // Verify the body is correctly base64 encoded
        val decodedBody = String(Base64.getDecoder().decode(deserialized.body))
        assertEquals(originalBody, decodedBody)
    }

    @Test
    fun `RequestPayload serialization - handles null body and query`() {
        val requestPayload = RequestPayload(
            method = "GET",
            path = "/api/simple",
            query = null,
            headers = mapOf("Accept" to "application/json"),
            body = null
        )

        val json = objectMapper.writeValueAsString(requestPayload)
        val deserialized = objectMapper.readValue(json, RequestPayload::class.java)

        assertEquals("GET", deserialized.method)
        assertEquals("/api/simple", deserialized.path)
        assertNull(deserialized.query)
        assertNull(deserialized.body)
    }

    @Test
    fun `ResponsePayload serialization - round-trip serialization`() {
        val responsePayload = ResponsePayload(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json", "X-Request-Id" to "req-123"),
            body = "{\"success\": true}"
        )

        val json = objectMapper.writeValueAsString(responsePayload)
        val deserialized = objectMapper.readValue(json, ResponsePayload::class.java)

        assertEquals(responsePayload.statusCode, deserialized.statusCode)
        assertEquals(responsePayload.headers, deserialized.headers)
        assertEquals(responsePayload.body, deserialized.body)
    }

    @Test
    fun `ResponsePayload serialization - handles null body`() {
        val responsePayload = ResponsePayload(
            statusCode = 204,
            headers = emptyMap(),
            body = null
        )

        val json = objectMapper.writeValueAsString(responsePayload)
        val deserialized = objectMapper.readValue(json, ResponsePayload::class.java)

        assertEquals(204, deserialized.statusCode)
        assertTrue(deserialized.headers.isEmpty())
        assertNull(deserialized.body)
    }

    @Test
    fun `ErrorPayload serialization - with all error codes`() {
        ErrorCode.entries.forEach { errorCode ->
            val errorPayload = ErrorPayload(
                code = errorCode,
                message = "Test error message for ${errorCode.code}"
            )

            val json = objectMapper.writeValueAsString(errorPayload)
            val deserialized = objectMapper.readValue(json, ErrorPayload::class.java)

            assertEquals(errorCode, deserialized.code)
            assertEquals(errorPayload.message, deserialized.message)
        }
    }

    @Test
    fun `ErrorCode fromString - finds correct enum values`() {
        assertEquals(ErrorCode.TIMEOUT, ErrorCode.fromString("TIMEOUT"))
        assertEquals(ErrorCode.UPSTREAM_ERROR, ErrorCode.fromString("UPSTREAM_ERROR"))
        assertEquals(ErrorCode.INVALID_REQUEST, ErrorCode.fromString("INVALID_REQUEST"))
        assertEquals(ErrorCode.SERVER_ERROR, ErrorCode.fromString("SERVER_ERROR"))
        assertEquals(ErrorCode.RATE_LIMITED, ErrorCode.fromString("RATE_LIMITED"))
        assertNull(ErrorCode.fromString("UNKNOWN_ERROR"))
    }

    @Test
    fun `ControlPayload serialization - with REGISTERED action`() {
        val controlPayload = ControlPayload(
            action = ControlPayload.ACTION_REGISTER,
            subdomain = "abc123xyz789",
            publicUrl = "https://abc123xyz789.relay.example.com"
        )

        val json = objectMapper.writeValueAsString(controlPayload)
        val deserialized = objectMapper.readValue(json, ControlPayload::class.java)

        assertEquals(ControlPayload.ACTION_REGISTER, deserialized.action)
        assertEquals("abc123xyz789", deserialized.subdomain)
        assertEquals("https://abc123xyz789.relay.example.com", deserialized.publicUrl)
    }

    @Test
    fun `ControlPayload serialization - with other actions`() {
        val actions = listOf(
            ControlPayload.ACTION_UNREGISTER,
            ControlPayload.ACTION_HEARTBEAT,
            ControlPayload.ACTION_STATUS
        )

        actions.forEach { action ->
            val controlPayload = ControlPayload(
                action = action,
                subdomain = null,
                publicUrl = null
            )

            val json = objectMapper.writeValueAsString(controlPayload)
            val deserialized = objectMapper.readValue(json, ControlPayload::class.java)

            assertEquals(action, deserialized.action)
            assertNull(deserialized.subdomain)
            assertNull(deserialized.publicUrl)
        }
    }

    @Test
    fun `Correlation ID matching - correlationId is preserved through serialization`() {
        val correlationId = "corr-12345-abcde-67890"
        val payload = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            mapOf("data" to "test")
        )
        val envelope = Envelope(
            correlationId = correlationId,
            type = MessageType.RESPONSE,
            timestamp = Instant.now(),
            payload = payload
        )

        val json = objectMapper.writeValueAsString(envelope)
        
        // Verify the correlationId is in the JSON
        assertTrue(json.contains("\"correlationId\":\"$correlationId\""))
        
        val deserialized = objectMapper.readValue(json, Envelope::class.java)
        assertEquals(correlationId, deserialized.correlationId)
    }

    @Test
    fun `Timestamp handling - timestamp is set correctly and serialized`() {
        val beforeCreation = Instant.now()
        
        val payload = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            mapOf("test" to "data")
        )
        val envelope = Envelope(
            correlationId = "test-123",
            type = MessageType.CONTROL,
            payload = payload
            // timestamp uses default value
        )
        
        val afterCreation = Instant.now()

        // Verify timestamp is between before and after
        assertTrue(envelope.timestamp.isAfter(beforeCreation) || envelope.timestamp == beforeCreation)
        assertTrue(envelope.timestamp.isBefore(afterCreation) || envelope.timestamp == afterCreation)

        val json = objectMapper.writeValueAsString(envelope)
        val deserialized = objectMapper.readValue(json, Envelope::class.java)

        assertEquals(envelope.timestamp, deserialized.timestamp)
        
        // Verify timestamp is in ISO-8601 format in JSON
        assertTrue(json.contains("\"timestamp\":\""))
    }

    @Test
    fun `Complete message flow - request to response with same correlationId`() {
        val correlationId = "flow-123-456"
        val requestPayload = RequestPayload(
            method = "GET",
            path = "/test",
            headers = mapOf("Accept" to "application/json")
        )
        
        val requestEnvelope = Envelope(
            correlationId = correlationId,
            type = MessageType.REQUEST,
            payload = objectMapper.valueToTree(requestPayload)
        )

        val responsePayload = ResponsePayload(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = "{\"result\": \"ok\"}"
        )
        
        val responseEnvelope = Envelope(
            correlationId = correlationId,
            type = MessageType.RESPONSE,
            payload = objectMapper.valueToTree(responsePayload)
        )

        // Both envelopes should have the same correlationId
        assertEquals(requestEnvelope.correlationId, responseEnvelope.correlationId)
        
        // Serialize and deserialize both
        val requestJson = objectMapper.writeValueAsString(requestEnvelope)
        val responseJson = objectMapper.writeValueAsString(responseEnvelope)
        
        val deserializedRequest = objectMapper.readValue(requestJson, Envelope::class.java)
        val deserializedResponse = objectMapper.readValue(responseJson, Envelope::class.java)
        
        assertEquals(correlationId, deserializedRequest.correlationId)
        assertEquals(correlationId, deserializedResponse.correlationId)
        assertEquals(MessageType.REQUEST, deserializedRequest.type)
        assertEquals(MessageType.RESPONSE, deserializedResponse.type)
    }
}

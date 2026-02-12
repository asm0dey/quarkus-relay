package org.relay.server.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class RequestContextTest {

    @BeforeEach
    fun setup() {
        MDC.clear()
    }

    @Test
    fun `Generate correlation ID - produces valid UUID format`() {
        val correlationId = RequestContext.generateCorrelationId()

        // Should be a valid UUID string
        assertNotNull(correlationId)
        assertTrue(correlationId.isNotEmpty())
        assertTrue(correlationId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
    }

    @Test
    fun `Generate correlation ID - produces unique values`() {
        val ids = (1..100).map { RequestContext.generateCorrelationId() }
        val uniqueIds = ids.toSet()

        assertEquals(100, uniqueIds.size, "All generated correlation IDs should be unique")
    }

    @Test
    fun `With correlation ID - sets MDC for the duration of the block`() {
        assertNull(MDC.get(RequestContext.CORRELATION_ID_KEY))

        val result = RequestContext.withCorrelationId("test-correlation-id") {
            assertEquals("test-correlation-id", MDC.get(RequestContext.CORRELATION_ID_KEY))
            "block-result"
        }

        assertEquals("block-result", result)
        // MDC should be cleared after the block
        assertNull(MDC.get(RequestContext.CORRELATION_ID_KEY))
    }

    @Test
    fun `With correlation ID - restores previous MDC value after block`() {
        MDC.put(RequestContext.CORRELATION_ID_KEY, "previous-id")

        val result = RequestContext.withCorrelationId("new-correlation-id") {
            assertEquals("new-correlation-id", MDC.get(RequestContext.CORRELATION_ID_KEY))
            "block-result"
        }

        assertEquals("block-result", result)
        // Should restore the previous value
        assertEquals("previous-id", MDC.get(RequestContext.CORRELATION_ID_KEY))
    }

    @Test
    fun `Current correlation ID - returns null when not set`() {
        assertNull(RequestContext.currentCorrelationId())
    }

    @Test
    fun `Current correlation ID - returns value from MDC when set`() {
        MDC.put(RequestContext.CORRELATION_ID_KEY, "mdc-correlation-id")
        assertEquals("mdc-correlation-id", RequestContext.currentCorrelationId())
    }

    @Test
    fun `Extension function withCorrelationId - generates ID when not provided`() {
        val result = org.relay.server.observability.withCorrelationId {
            val id = MDC.get(RequestContext.CORRELATION_ID_KEY)
            assertNotNull(id)
            assertTrue(id.isNotEmpty())
            "done"
        }

        assertEquals("done", result)
    }

    @Test
    fun `Extension function withCorrelationId - uses provided ID`() {
        val result = org.relay.server.observability.withCorrelationId("custom-id") {
            assertEquals("custom-id", MDC.get(RequestContext.CORRELATION_ID_KEY))
            "done"
        }

        assertEquals("done", result)
    }
}

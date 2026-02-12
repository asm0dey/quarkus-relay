package org.relay.server.observability

import io.quarkus.arc.Arc
import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import org.slf4j.MDC
import java.util.UUID

/**
 * Request context for tracking correlation IDs across the request lifecycle.
 * Provides observability and distributed tracing capabilities for HTTP requests
 * and WebSocket messages processed by the relay server.
 *
 * This class follows Constitution Principle V: Observability by Design
 *
 * @property correlationId The unique identifier for correlating log entries and tracing requests
 */
@Unremovable
@RequestScoped
class RequestContext @Inject constructor() {

    private var _correlationId: String = generateCorrelationId()

    /**
     * The correlation ID for this request context.
     * Used to trace requests through the system for debugging and observability.
     */
    var correlationId: String
        get() = _correlationId
        set(value) {
            _correlationId = value
            MDC.put(CORRELATION_ID_KEY, value)
        }

    /**
     * Initializes the context with a new correlation ID.
     * Called automatically when the context is created.
     */
    init {
        MDC.put(CORRELATION_ID_KEY, _correlationId)
    }

    /**
     * Clears the correlation ID from MDC when the context is destroyed.
     */
    fun clear() {
        MDC.remove(CORRELATION_ID_KEY)
    }

    companion object {
        const val CORRELATION_ID_KEY = "correlationId"
        const val CORRELATION_ID_HEADER = "X-Request-Id"

        /**
         * Generates a new unique correlation ID.
         *
         * @return A unique correlation ID string
         */
        fun generateCorrelationId(): String {
            return UUID.randomUUID().toString()
        }

        /**
         * Gets the current request context instance.
         * This allows accessing the context from non-CDI managed classes.
         *
         * @return The current RequestContext or null if not in a request scope
         */
        fun current(): RequestContext? {
            return try {
                Arc.container().instance(RequestContext::class.java).get()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Gets the current correlation ID from the request context or MDC.
         *
         * @return The current correlation ID or null if not available
         */
        fun currentCorrelationId(): String? {
            return current()?.correlationId ?: MDC.get(CORRELATION_ID_KEY)
        }

        /**
         * Executes a block of code with a specific correlation ID.
         * The correlation ID is set in MDC for the duration of the block and
         * restored to its previous value afterwards.
         *
         * @param correlationId The correlation ID to use
         * @param block The code block to execute
         * @return The result of the code block
         */
        inline fun <T> withCorrelationId(correlationId: String, block: () -> T): T {
            val previousId = MDC.get(CORRELATION_ID_KEY)
            try {
                MDC.put(CORRELATION_ID_KEY, correlationId)
                return block()
            } finally {
                if (previousId != null) {
                    MDC.put(CORRELATION_ID_KEY, previousId)
                } else {
                    MDC.remove(CORRELATION_ID_KEY)
                }
            }
        }
    }
}

/**
 * Extension function to run code with correlation ID tracking.
 * Automatically generates a correlation ID if none is provided.
 *
 * @param correlationId Optional correlation ID (generated if not provided)
 * @param block The code block to execute with correlation tracking
 * @return The result of the code block
 */
inline fun <T> withCorrelationId(correlationId: String? = null, block: () -> T): T {
    val id = correlationId ?: RequestContext.generateCorrelationId()
    return RequestContext.withCorrelationId(id, block)
}

package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Envelope for all protocol messages. Contains metadata and the payload.
 */
data class Envelope(
    @JsonProperty("correlationId")
    val correlationId: String,

    @JsonProperty("type")
    val type: MessageType,

    @JsonProperty("timestamp")
    val timestamp: Instant = Instant.now(),

    @JsonProperty("payload")
    val payload: JsonNode
) {
    companion object {
        const val CORRELATION_ID_FIELD = "correlationId"
        const val TYPE_FIELD = "type"
        const val TIMESTAMP_FIELD = "timestamp"
        const val PAYLOAD_FIELD = "payload"
    }

    override fun toString(): String {
        return "Envelope(correlationId='$correlationId', type=$type, timestamp=$timestamp, payload=${payload.toPrettyString().take(500)})"
    }
}

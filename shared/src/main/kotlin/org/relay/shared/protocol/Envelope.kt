package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Envelope for all protocol messages. Contains metadata and the payload.
 */
data class Envelope(
    @field:JsonProperty("correlationId")
    val correlationId: String,

    @field:JsonProperty("type")
    val type: MessageType,

    @field:JsonProperty("timestamp")
    val timestamp: Instant = Instant.now(),

    @field:JsonProperty("payload")
    val payload: JsonNode
)

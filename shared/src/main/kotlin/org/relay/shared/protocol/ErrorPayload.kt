package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for ERROR messages containing error details.
 */
data class ErrorPayload(
    @field:JsonProperty("code")
    val code: ErrorCode,

    @field:JsonProperty("message")
    val message: String
)

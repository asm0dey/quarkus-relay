package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for ERROR messages containing error details.
 */
data class ErrorPayload(
    @JsonProperty("code")
    val code: ErrorCode,

    @JsonProperty("message")
    val message: String
) {
    companion object {
        const val CODE_FIELD = "code"
        const val MESSAGE_FIELD = "message"
    }

    override fun toString(): String {
        return "ErrorPayload(code=$code, message='$message')"
    }
}

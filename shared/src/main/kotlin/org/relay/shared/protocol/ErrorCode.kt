package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Enumeration of error codes used in ErrorPayload.
 */
enum class ErrorCode(
    @field:JsonProperty("code")
    val code: String
) {
    TIMEOUT("TIMEOUT"),
    UPSTREAM_ERROR("UPSTREAM_ERROR"),
    INVALID_REQUEST("INVALID_REQUEST"),
    SERVER_ERROR("SERVER_ERROR"),
    RATE_LIMITED("RATE_LIMITED");

    companion object {
        /**
         * Finds an ErrorCode by its string value.
         */
        fun fromString(value: String): ErrorCode? {
            return entries.find { it.code == value }
        }
    }

    override fun toString(): String {
        return code
    }
}

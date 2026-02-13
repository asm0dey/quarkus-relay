package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Enumeration of message types used in the Relay protocol.
 */
enum class MessageType(
    @field:JsonProperty("type")
    val type: String
) {
    REQUEST("REQUEST"),
    RESPONSE("RESPONSE"),
    ERROR("ERROR"),
    CONTROL("CONTROL");

    companion object {
        /**
         * Finds a MessageType by its string value.
         */
        fun fromString(value: String): MessageType? {
            return entries.find { it.type == value }
        }
    }

    override fun toString(): String {
        return type
    }
}

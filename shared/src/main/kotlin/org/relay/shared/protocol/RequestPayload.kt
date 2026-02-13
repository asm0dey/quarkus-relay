package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for REQUEST messages containing HTTP request data.
 */
data class RequestPayload(
    @field:JsonProperty("method")
    val method: String,

    @field:JsonProperty("path")
    val path: String,

    @field:JsonProperty("query")
    val query: Map<String, String>? = null,

    @field:JsonProperty("headers")
    val headers: Map<String, String>,

    @field:JsonProperty("body")
    val body: String? = null,

    @field:JsonProperty("webSocketUpgrade")
    val webSocketUpgrade: Boolean = false
)

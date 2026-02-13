package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for RESPONSE messages containing HTTP response data.
 */
data class ResponsePayload(
    @field:JsonProperty("statusCode")
    val statusCode: Int,

    @field:JsonProperty("headers")
    val headers: Map<String, String>,

    @field:JsonProperty("body")
    val body: String? = null
)

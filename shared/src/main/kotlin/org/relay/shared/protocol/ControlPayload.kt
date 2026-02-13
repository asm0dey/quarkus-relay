package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for CONTROL messages for administrative and configuration actions.
 */
data class ControlPayload(
    @field:JsonProperty("action")
    val action: String,

    @field:JsonProperty("subdomain")
    val subdomain: String? = null,

    @field:JsonProperty("publicUrl")
    val publicUrl: String? = null
) {
    companion object {
        // Common control actions
        const val ACTION_REGISTER = "REGISTER"
        const val ACTION_REGISTERED = "REGISTERED"
        const val ACTION_UNREGISTER = "UNREGISTER"
        const val ACTION_HEARTBEAT = "HEARTBEAT"
        const val ACTION_STATUS = "STATUS"
    }
}

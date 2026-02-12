package org.relay.shared.protocol

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Payload for CONTROL messages for administrative and configuration actions.
 */
data class ControlPayload(
    @JsonProperty("action")
    val action: String,

    @JsonProperty("subdomain")
    val subdomain: String? = null,

    @JsonProperty("publicUrl")
    val publicUrl: String? = null
) {
    companion object {
        const val ACTION_FIELD = "action"
        const val SUBDOMAIN_FIELD = "subdomain"
        const val PUBLIC_URL_FIELD = "publicUrl"

        // Common control actions
        const val ACTION_REGISTER = "REGISTER"
        const val ACTION_UNREGISTER = "UNREGISTER"
        const val ACTION_HEARTBEAT = "HEARTBEAT"
        const val ACTION_STATUS = "STATUS"
    }

    override fun toString(): String {
        return "ControlPayload(action='$action', subdomain=$subdomain, publicUrl=$publicUrl)"
    }
}

package org.relay.server.integration

import io.quarkus.test.junit.QuarkusTestProfile

class IntegrationTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "relay.domain" to "localhost",
        "relay.secret-keys" to "test-secret-key,test-secret-key-12345",
        "relay.request-timeout" to "PT5S",
        "relay.max-body-size" to "1048576",
        "quarkus.http.port" to "8081",
        "quarkus.http.test-port" to "0",
        "quarkus.log.level" to "INFO",
        "quarkus.log.category.\"org.relay.server.websocket\".level" to "DEBUG"
    )
}

package org.relay.server.config

import io.quarkus.runtime.annotations.RegisterForReflection
import org.relay.shared.protocol.*

/**
 * Registers protocol classes for reflection.
 * This is required for Jackson deserialization to work in native image,
 * especially since these classes are in a shared module that doesn't depend on Quarkus.
 */
@RegisterForReflection(
    targets = [
        Envelope::class,
        MessageType::class,
        ControlPayload::class,
        ErrorPayload::class,
        ErrorCode::class,
        RequestPayload::class,
        ResponsePayload::class,
        WebSocketFramePayload::class
    ]
)
class ReflectionConfiguration

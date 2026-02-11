# Implementation Plan: Relay Tunnel Service

**Branch**: `001-relay-tunnel` | **Date**: 2026-02-11 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-relay-tunnel/spec.md`

## Summary

Build a tunneling service consisting of two main components:
1. **Server**: A Quarkus-based relay server that accepts authenticated client connections via WebSocket, assigns random subdomains, and forwards HTTP/WebSocket requests from public subdomains to connected clients.
2. **Client**: A command-line application that connects to the server with a secret key, receives forwarded requests, proxies them to a local application, and returns responses.

The system enables developers to expose local applications to the internet through publicly accessible subdomains without complex network configuration.

## Technical Context

**Language/Version**: Kotlin 2.x with Java 21 target
**Primary Framework**: Quarkus 3.x with the following extensions:
- `quarkus-websockets` - For persistent client-server connections
- `quarkus-vertx` - For reactive HTTP handling and proxying
- `quarkus-config-yaml` - For flexible configuration
- `quarkus-smallrye-health` - For health checks
- `quarkus-micrometer` - For metrics and observability
**Storage**: In-memory only (no persistence required)
**Testing**: JUnit 5, RestAssured, Testcontainers for integration tests
**Target Platform**: Linux server deployment (JVM mode), CLI client (JVM or native)
**Project Type**: Multi-module Gradle project with separate `server` and `client` modules
**Performance Goals**: 
- Support 100+ concurrent tunnels
- <50ms latency for request forwarding under normal load
- Handle requests up to 10MB body size
**Constraints**:
- Memory usage: <512MB for server under normal load
- WebSocket connections must auto-reconnect on client side
- Support HTTP/1.1 and HTTP/2
**Scale/Scope**: Single-server deployment (horizontal scaling out of scope for MVP)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Specification-Driven | ✓ | Following spec.md with clear user stories and acceptance criteria |
| II. Progressive Disclosure | ✓ | Starting with single-server MVP; horizontal scaling deferred |
| III. Test-First Verification | ⚠️ | Test plan to be created in Phase 2; integration tests required |
| IV. Modularity and Clear Boundaries | ✓ | Clear separation between server and client modules; WebSocket protocol as contract |
| V. Observability by Design | ✓ | Structured logging with SLF4J/Logback; metrics via Micrometer |

## Phase 0: Research

### 0.1 Quarkus WebSocket Implementation

**Research Question**: How to implement bidirectional WebSocket communication between client and server for request/response multiplexing?

**Findings**:
- Quarkus provides `@ServerEndpoint` and `@ClientEndpoint` annotations for WebSocket handling
- For multiplexing multiple concurrent requests over a single WebSocket:
  - Use request correlation IDs to match responses with requests
  - Implement a message protocol with envelope format (correlationId, payload, metadata)
  - Server sends requests to client; client responds with same correlationId
- Alternative: SockJS or raw WebSocket - raw WebSocket sufficient for this use case

**Decision**: Use Quarkus WebSocket API with custom message protocol for request/response correlation.

### 0.2 HTTP Proxying and Forwarding

**Research Question**: How to forward HTTP requests from server to client and stream responses back?

**Findings**:
- Vert.x `HttpClient` provides proxy capabilities
- For request forwarding:
  1. External request arrives at server subdomain endpoint
  2. Server looks up associated WebSocket client
  3. Server serializes HTTP request (method, headers, body) into message
  4. Server sends to client via WebSocket
  5. Client deserializes and makes local HTTP request
  6. Client serializes response and sends back
  7. Server streams response to original requester
- Body streaming: Need to handle chunked transfer for large bodies
- Timeout handling: Configure timeouts for local application requests

**Decision**: Implement custom HTTP-over-WebSocket protocol with streaming support for request/response bodies.

### 0.3 Subdomain Routing

**Research Question**: How to route incoming HTTP requests based on subdomain to the correct tunnel?

**Findings**:
- Wildcard DNS required (e.g., `*.tun.example.com` → server IP)
- Quarkus HTTP routing can inspect `Host` header
- Implement a Vert.x route handler that:
  1. Extracts subdomain from `Host` header
  2. Looks up active tunnel in registry
  3. Forwards request or returns 404 if no tunnel found
- TLS termination: Can be handled at reverse proxy (nginx/traefik) or via Quarkus TLS

**Decision**: Implement subdomain-based routing using Vert.x route handler inspecting Host header; TLS termination at reverse proxy layer.

### 0.4 Random Subdomain Generation

**Research Question**: How to generate random, collision-resistant subdomains?

**Findings**:
- Options: UUID (too long), random alphanumeric (8-12 chars), memorable words (petname)
- Collision probability for 10-char alphanumeric: ~1 in 3.6 billion
- Need in-memory registry to detect and prevent collisions
- Subdomain format: `[a-z0-9]{10}` or similar

**Decision**: Use 12-character lowercase alphanumeric random strings; check against active tunnel registry.

## Phase 1: Design

### 1.1 Architecture Overview

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│   External      │         │   Relay Server   │         │   Local App     │
│   User          │────────▶│   (Quarkus)      │◀───────▶│   (Any HTTP)    │
│                 │  HTTP   │                  │  HTTP   │                 │
└─────────────────┘         └────────┬─────────┘         └─────────────────┘
                                     │
                                     │ WebSocket
                                     │
                              ┌──────▼─────────┐
                              │   Tunnel       │
                              │   Client       │
                              │   (CLI App)    │
                              └────────────────┘
```

### 1.2 Message Protocol

WebSocket messages use JSON envelope format:

```json
{
  "correlationId": "uuid-string",
  "type": "REQUEST|RESPONSE|ERROR|CONTROL",
  "payload": {
    // Request: HTTP request details
    // Response: HTTP response details
    // Error: Error information
    // Control: Connection management
  }
}
```

**Request Payload**:
```json
{
  "method": "GET|POST|...",
  "path": "/api/status",
  "headers": {"Content-Type": "application/json"},
  "body": "base64-encoded-body"
}
```

**Response Payload**:
```json
{
  "statusCode": 200,
  "headers": {"Content-Type": "application/json"},
  "body": "base64-encoded-body"
}
```

### 1.3 Component Design

#### Server Components

1. **TunnelRegistry** (Singleton)
   - Maintains map of subdomain → TunnelConnection
   - Thread-safe operations
   - Methods: `register`, `unregister`, `getBySubdomain`

2. **TunnelWebSocketEndpoint** (`@ServerEndpoint`)
   - Handles client WebSocket connections
   - Authenticates using secret key from connection header
   - Assigns random subdomain
   - Manages message routing

3. **SubdomainRoutingHandler** (Vert.x Route)
   - Intercepts all HTTP requests
   - Extracts subdomain from Host header
   - Routes to appropriate tunnel or returns 404

4. **RequestForwarder**
   - Serializes HTTP requests into messages
   - Sends to client via WebSocket
   - Waits for response with timeout
   - Streams response back to original requester

#### Client Components

1. **TunnelClient** (Main class)
   - Parses command-line arguments
   - Establishes WebSocket connection
   - Handles reconnection logic

2. **WebSocketClientEndpoint** (`@ClientEndpoint`)
   - Manages connection to server
   - Handles incoming request messages
   - Sends response messages

3. **LocalHttpProxy**
   - Makes HTTP requests to local application
   - Serializes responses
   - Handles timeouts and errors

### 1.4 Data Model

```kotlin
// Server-side entities
data class TunnelConnection(
    val subdomain: String,
    val session: Session,  // WebSocket session
    val createdAt: Instant,
    val metadata: Map<String, String>
)

data class PendingRequest(
    val correlationId: String,
    val responseFuture: CompletableFuture<ResponsePayload>,
    val timeout: ScheduledFuture<*>
)

// Message types
enum class MessageType { REQUEST, RESPONSE, ERROR, CONTROL }

data class Envelope(
    val correlationId: String,
    val type: MessageType,
    val payload: JsonObject
)
```

### 1.5 Configuration

**Server Configuration** (`application.yml`):
```yaml
relay:
  domain: "tun.asm0dey.site"
  secret-keys: "${RELAY_SECRET_KEYS}"  # Comma-separated
  request-timeout: 30s
  max-body-size: 10MB
  subdomain-length: 12

quarkus:
  http:
    port: 8080
```

**Client Configuration** (command-line args):
```bash
./tunnel-client \
  --server-url wss://tun.asm0dey.site/ws \
  --secret-key my-secret \
  --local-url http://localhost:3000 \
  [--subdomain custom-name]  # Optional: request specific subdomain
```

### 1.6 Security Considerations

1. **Authentication**: Secret key validation on WebSocket connection
2. **Authorization**: Each client only receives requests for its assigned subdomain
3. **Input Validation**: Validate all incoming HTTP headers and body sizes
4. **Rate Limiting**: Consider per-tunnel rate limits (MVP: manual nginx/traefik config)
5. **TLS**: Required for production; terminate at reverse proxy or Quarkus level

### 1.7 Error Handling

| Scenario | Server Behavior | Client Behavior |
|----------|-----------------|-----------------|
| Invalid secret key | Close WebSocket with 401 | Exit with error message |
| Client disconnect | Remove tunnel from registry; return 503 for new requests | Auto-reconnect with backoff |
| Local app timeout | Return 504 Gateway Timeout | Log error, send error response |
| Local app unreachable | Return 502 Bad Gateway | Log error, send error response |
| Request body too large | Return 413 Payload Too Large | N/A |
| Subdomain not found | Return 404 Not Found | N/A |

### 1.8 Observability

**Metrics** (Micrometer):
- `relay.tunnels.active`: Gauge of active tunnels
- `relay.requests.total`: Counter of proxied requests
- `relay.requests.duration`: Timer of request latency
- `relay.requests.errors`: Counter of failed requests

**Logging**:
- Structured JSON logging
- Correlation IDs for request tracing
- Log levels: INFO for connections/disconnections, DEBUG for request details

## Phase 2: Testing Strategy

### 2.1 Unit Tests

- **TunnelRegistry**: Register/unregister/lookup operations
- **Message serialization/deserialization**
- **Subdomain generation**: Collision handling
- **Configuration parsing**

### 2.2 Integration Tests

**Testcontainers-based tests**:
1. Start server
2. Connect client with secret key
3. Make HTTP request to assigned subdomain
4. Verify request reaches mock local application
5. Verify response flows back correctly

**Scenario coverage**:
- Successful tunnel creation and request forwarding
- Invalid secret key rejection
- Client disconnection handling
- Concurrent tunnels
- Large request/response bodies
- WebSocket upgrade requests

### 2.3 Contract Tests

- WebSocket message format validation
- HTTP request/response serialization format

## Project Structure

### Documentation (this feature)

```text
specs/001-relay-tunnel/
  plan.md              # This file
  research.md          # Phase 0 research notes (optional)
  data-model.md        # Phase 1 data model details (optional)
  contracts/           # Message format contracts
    websocket-protocol.md
  checklists/
    requirements.md    # Spec quality checklist
  tasks.md             # Generated by /iikit-06-tasks
```

### Source Code (repository root)

```text
# Multi-module Gradle project
server/                     # Relay server (Quarkus application)
  src/
    main/kotlin/
      org/relay/server/
        Application.kt
        websocket/
          TunnelWebSocketEndpoint.kt
        routing/
          SubdomainRoutingHandler.kt
        tunnel/
          TunnelRegistry.kt
          TunnelConnection.kt
        forwarder/
          RequestForwarder.kt
        config/
          RelayConfig.kt
        metrics/
          MetricsConfiguration.kt
    test/kotlin/
      # Unit and integration tests
  build.gradle.kts

client/                     # Tunnel client (CLI application)
  src/
    main/kotlin/
      org/relay/client/
        TunnelClient.kt
        websocket/
          WebSocketClientEndpoint.kt
        proxy/
          LocalHttpProxy.kt
        config/
          ClientConfig.kt
        retry/
          ReconnectionHandler.kt
    test/kotlin/
      # Unit and integration tests
  build.gradle.kts

shared/                     # Shared message types (optional)
  src/main/kotlin/
    org/relay/shared/
      protocol/
        MessageTypes.kt
        Envelope.kt
        RequestPayload.kt
        ResponsePayload.kt

build.gradle.kts            # Root project build file
settings.gradle.kts         # Multi-module settings
docker-compose.yml          # For local development/testing
```

**Structure Decision**: Multi-module Gradle project with `server`, `client`, and optionally `shared` modules. Server is a Quarkus application, client is a Kotlin CLI application using Quarkus as a framework for dependency injection and configuration.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations identified. All constitution principles can be satisfied with this design.

## Phase 3: Task Breakdown (High Level)

1. **Setup Phase**
   - Configure multi-module Gradle project structure
   - Set up Quarkus dependencies for server and client
   - Configure testing framework with Testcontainers

2. **Server Implementation**
   - Implement TunnelRegistry for connection management
   - Create WebSocket endpoint for client connections
   - Implement subdomain-based HTTP routing
   - Build request forwarding logic with message protocol
   - Add configuration and metrics

3. **Client Implementation**
   - Create CLI argument parsing
   - Implement WebSocket client connection
   - Build local HTTP proxy functionality
   - Add reconnection logic

4. **Testing**
   - Write unit tests for components
   - Create integration tests with Testcontainers
   - Performance testing for concurrent tunnels

5. **Documentation**
   - README with setup instructions
   - Architecture decision records
   - Deployment guide

---

**Next Step**: Run `/iikit-04-checklist` to generate additional quality checklists, then `/iikit-05-testify` for test specifications, followed by `/iikit-06-tasks` for detailed task breakdown.

# Feature Specification: Relay Tunnel Service

**Feature Branch**: `001-relay-tunnel`
**Created**: 2026-02-11
**Status**: Draft
**Input**: User description: "I need a quarkus app, functioning as ngrok: I should be able to to connect to the server with a secret key, server should start answering a random-named subdomain of a domain I set in settings. For example, if the main domain is tun.asm0dey.site, then random subdomain can be random-subdomain.tun.asm0dey.site. It should forward all the requests to the client of the constantly open channel. In another module I need to have a client that will be able to connect to the server with a secret key and act as a proxy between server and an actual application we're proxying"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Client Connects and Registers Tunnel (Priority: P1)

As a developer, I want to run a local client that connects to the relay server using a secret key, so that I can expose my local application to the internet through a public subdomain.

**Why this priority**: This is the core value proposition of the entire system. Without the ability for a client to connect and register a tunnel, no other functionality matters. This represents the minimum viable product.

**Independent Test**: Can be tested by starting the server, running the client with a valid secret key, and verifying the client reports a successful connection with an assigned subdomain.

**Acceptance Scenarios**:

1. **Given** the relay server is running with domain `tun.example.com` configured, **When** a client connects with a valid secret key, **Then** the server assigns a random subdomain (e.g., `abc123.tun.example.com`) and the client receives confirmation with the assigned URL.

2. **Given** a client is attempting to connect, **When** the client provides an invalid secret key, **Then** the connection is rejected with an authentication error and the client is informed of the failure.

3. **Given** the relay server is unreachable, **When** the client attempts to connect, **Then** the client receives a clear connection error and can retry.

---

### User Story 2 - HTTP Request Forwarding (Priority: P1)

As a user with an active tunnel, I want HTTP requests made to my assigned public subdomain to be forwarded to my local application, so that external users can access my local service.

**Why this priority**: This is the second critical capability - the actual request forwarding. Combined with US1, this completes the core tunnel functionality that delivers value to users.

**Independent Test**: Can be tested by making an HTTP request to the assigned subdomain and verifying it reaches the local application, with the response flowing back to the requester.

**Acceptance Scenarios**:

1. **Given** a client has an active tunnel with subdomain `abc123.tun.example.com`, **When** an HTTP GET request is made to `http://abc123.tun.example.com/api/status`, **Then** the request is forwarded to the client's local application and the response is returned to the original requester.

2. **Given** an active tunnel, **When** a POST request with a JSON body is made to the public subdomain, **Then** the complete request (headers, body, method) is preserved and forwarded to the local application.

3. **Given** a tunnel exists but the local application is not responding, **When** a request is made to the public subdomain, **Then** the requester receives an appropriate error response indicating the upstream service is unavailable.

---

### User Story 3 - Multiple Concurrent Tunnels (Priority: P2)

As a team lead, I want multiple clients to connect simultaneously with different subdomains, so that my team members can each expose their own local services independently.

**Why this priority**: While the system works for a single user without this, supporting multiple concurrent tunnels makes the service usable for teams and represents a significant value multiplier.

**Independent Test**: Can be tested by connecting multiple clients with different secret keys, verifying each gets a unique subdomain, and confirming requests to each subdomain route to the correct client.

**Acceptance Scenarios**:

1. **Given** the server is running, **When** three clients connect with different valid secret keys, **Then** each client receives a unique random subdomain and all three can maintain simultaneous connections.

2. **Given** three active tunnels with unique subdomains, **When** requests are made to each subdomain, **Then** each request is forwarded to the correct corresponding client.

3. **Given** two clients connect with the same secret key, **When** the second client connects, **Then** both clients receive unique subdomains and operate independently (same key allows multiple tunnels).

---

### User Story 4 - Configuration and Domain Management (Priority: P3)

As a system administrator, I want to configure the base domain and other server settings, so that I can deploy the relay service with my own domain and security policies.

**Why this priority**: This enables self-hosting and customization but is not required for basic functionality. It supports operational flexibility rather than core user value.

**Independent Test**: Can be tested by starting the server with custom configuration and verifying it uses the specified domain and settings.

**Acceptance Scenarios**:

1. **Given** a configuration file or environment variables specifying `BASE_DOMAIN=tun.mycompany.com`, **When** the server starts, **Then** assigned subdomains follow the pattern `*.tun.mycompany.com`.

2. **Given** configuration for secret key validation, **When** the server processes client connections, **Then** only requests with keys matching the configured validation rules are accepted.

---

### User Story 5 - WebSocket Forwarding (Priority: P2)

As a developer with a real-time application, I want WebSocket connections from external users to be forwarded through the tunnel to my local application, so that I can develop and test WebSocket-based features locally.

**Why this priority**: Many modern applications use WebSockets for real-time features. Supporting WebSocket forwarding makes the tunnel service suitable for full-stack development.

**Independent Test**: Can be tested by establishing a WebSocket connection to the assigned subdomain and verifying bidirectional message flow works.

**Acceptance Scenarios**:

1. **Given** a client has an active tunnel, **When** an external user opens a WebSocket connection to `wss://abc123.tun.example.com/ws`, **Then** the WebSocket upgrade request is forwarded to the local application and the bidirectional connection is established.

2. **Given** an active WebSocket tunnel, **When** the external user sends a message, **Then** the message is forwarded to the local application.

3. **Given** an active WebSocket tunnel, **When** the local application sends a message, **Then** the message is forwarded to the external user.

---

### Edge Cases

- **Client disconnect timeout**: When a client disconnects unexpectedly, the server waits 30 seconds before considering the tunnel closed (grace period for in-flight requests).
- **Subdomain collisions**: The system detects duplicate random subdomains and regenerates until a unique one is found (12-character alphanumeric provides ~1 in 3.6 billion collision probability).
- **Local application timeout**: If the local application doesn't respond within 30 seconds, the server returns 504 Gateway Timeout to the external requester.
- **Large request/response bodies**: Bodies up to 10MB are supported via Base64 encoding in WebSocket messages; larger requests return 413 Payload Too Large.
- **In-flight requests on disconnect**: When a client disconnects while requests are in-flight, those requests immediately receive 503 Service Unavailable.
- **Malformed HTTP requests**: The server validates incoming HTTP and returns 400 Bad Request for malformed requests.
- **Concurrent connection limits**: No artificial limits per tunnel or client; constrained by server resources (memory, file descriptors).
- **Non-HTTP local response**: If the local application returns a malformed or non-HTTP response, the server returns 502 Bad Gateway to the external requester.
- **Invalid WebSocket message**: If a WebSocket message has invalid format or unknown message type, the connection is closed with error code 1008 (policy violation).
- **Resource exhaustion**: When server reaches resource limits (memory, file descriptors), new tunnel connections are rejected with 503 Service Unavailable until resources free up.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST allow clients to establish authenticated connections to the server using a secret key.
- **FR-002**: The server MUST generate a unique random subdomain for each connected client.
- **FR-003**: The server MUST reject connections with invalid or missing secret keys.
- **FR-004**: The server MUST forward all HTTP requests received on assigned subdomains to the corresponding connected client.
- **FR-005**: The client MUST forward received requests to a configured local application and return the response to the server.
- **FR-006**: The server MUST return the client's application response to the original requester.
- **FR-007**: The system MUST support multiple concurrent client connections, each with a unique subdomain.
- **FR-008**: The server configuration MUST allow setting the base domain for subdomain generation.
- **FR-009**: The client MUST maintain a persistent connection to the server for receiving requests.
- **FR-010**: The system MUST forward WebSocket connections from external users to the local application through the tunnel (end-to-end WebSocket proxying).
- **FR-011**: The server MUST support configurable shutdown behavior: graceful (wait for in-flight requests with timeout) or immediate (close immediately).
- **FR-012**: The client MUST implement exponential backoff reconnection strategy: initial delay 1 second, doubling each retry, capped at 60 seconds, with infinite retries.

### Key Entities

- **Client**: The tunnel client application that connects to the server and proxies requests to a local application. Key attributes: secret key, assigned subdomain, connection state, local target endpoint.
- **Server**: The relay server that accepts client connections and routes external requests. Key attributes: base domain, active tunnels registry, authentication configuration.
- **Tunnel**: An active connection between a client and the server representing a routable subdomain. Key attributes: subdomain name, client reference, creation time, request statistics.
- **Request**: An HTTP/WebSocket request being forwarded through the tunnel. Key attributes: method, headers, body, target subdomain.

## Clarifications

### Session 2026-02-11

- Q: When a second client connects with the same secret key, what should happen? -> A: Multiple clients can connect with the same key, each getting their own unique subdomain
- Q: How long should the server wait before cleaning up a disconnected tunnel? -> A: 30 seconds
- Q: Should the MVP support forwarding WebSocket connections from external users? -> A: Yes, full WebSocket forwarding required
- Q: How long should the server wait for a response from the local application? -> A: 30 seconds
- Q: What happens to in-flight requests when a client disconnects? -> A: Fail immediately with 503

## Assumptions

- **Wildcard DNS**: The deployment environment has wildcard DNS configured (e.g., `*.tun.example.com` → server IP).
- **Reverse Proxy for TLS**: Production deployments use a reverse proxy (nginx, traefik, etc.) for TLS termination; Quarkus native TLS is optional.
- **HTTP-Compliant Local Application**: The local application behind the client follows HTTP/1.1 or HTTP/2 protocol specifications.

## Dependencies

- **WebSocket Support**: Both server and client runtime environments support WebSocket protocol (RFC 6455).
- **Network Connectivity**: Client has outbound internet access to reach the relay server.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can complete the full setup (start server, start client, receive subdomain) in under 5 minutes.
- **SC-002**: HTTP requests to assigned subdomains are forwarded to the local application with less than 100ms additional latency (excluding network latency) under normal load (defined as: up to 100 concurrent tunnels, 1000 requests/second total).
- **SC-003**: The system supports at least 100 concurrent active tunnels without degradation (degradation defined as: latency increase >20%, error rate >1%, or throughput drop >15%).
- **SC-004**: Invalid secret keys are rejected 100% of the time with no false positives.
- **SC-005**: When the local application is healthy, 99.9% of proxied requests complete successfully under normal load (100 concurrent tunnels, 1000 req/s).
- **SC-006**: Subdomain collisions occur with probability less than 1 in 1 million (based on random subdomain generation strategy).

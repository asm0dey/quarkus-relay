# Tasks: Relay Tunnel Service

**Feature**: 001-relay-tunnel  
**Generated**: 2026-02-11T23:41:04Z  
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Tests**: [test-specs.md](tests/test-specs.md)

---

## Plan Readiness Report

```
+-----------------------------------------------+
|  PLAN READINESS REPORT                        |
+-----------------------------------------------+
|  Tech Stack:       Kotlin 2.x / Quarkus 3.x   |
|  User Stories:     5 found (P1: 2, P2: 2, P3: 1)
|  Shared Entities:  Tunnel, Envelope -> Phase 2|
|  API Contracts:    1 WebSocket protocol       |
|  Test Specs:       34 tests generated         |
+-----------------------------------------------+
|  TASK GENERATION: READY                       |
+-----------------------------------------------+
```

---

## Phase 1: Project Setup

**Objective**: Initialize multi-module Gradle project with Quarkus dependencies

- [x] T001 Create multi-module Gradle project structure (server/, client/, shared/ modules)
- [x] T002 Configure root build.gradle.kts with Kotlin 2.x and Java 21 settings
- [x] T003 [P] Configure server module with Quarkus 3.x dependencies (websockets, vertx, config-yaml, health, micrometer)
- [x] T004 [P] Configure client module with Quarkus 3.x dependencies (websockets, picocli)
- [x] T005 [P] Create shared module for protocol message types (Envelope, RequestPayload, ResponsePayload)
- [x] T006 Configure Testcontainers for integration testing (included in build.gradle.kts)
- [x] T007 Create basic application.yml templates for server and client configuration

---

## Phase 2: Foundational Components

**Objective**: Build shared infrastructure required by all user stories

### Shared Protocol (Blocks: US1, US2, US3, US4, US5)

- [x] T101 Create Envelope data class with correlationId, type, timestamp, payload (for TS-101)
- [x] T102 Create MessageType enum (REQUEST, RESPONSE, ERROR, CONTROL)
- [x] T103 Create RequestPayload data class (method, path, query, headers, base64 body) (for TS-102)
- [x] T104 Create ResponsePayload data class (statusCode, headers, base64 body) (for TS-103)
- [x] T105 Create ErrorPayload data class with error codes (TIMEOUT, UPSTREAM_ERROR, INVALID_REQUEST, SERVER_ERROR, RATE_LIMITED) (for TS-104)
- [x] T106 Create ControlPayload data class for tunnel registration (for TS-105)
- [x] T107 Implement JSON serialization/deserialization for all message types (via Jackson annotations)
- [x] T108 Write unit tests for message serialization (TS-101 through TS-106)

### Server Core Infrastructure (Blocks: US1, US2)

- [x] T201 Create TunnelConnection data class (subdomain, session, createdAt, metadata)
- [x] T202 Create PendingRequest class for tracking in-flight requests
- [x] T203 Implement SubdomainGenerator service with 12-char alphanumeric generation (for TS-302)
- [x] T204 [P] Implement TunnelRegistry singleton with thread-safe register/lookup/unregister (for TS-301)
- [x] T205 Create RelayConfig configuration class for server settings (for TS-303)
- [x] T206 Implement RequestContext with correlation ID tracking for observability
- [x] T207 Write unit tests for TunnelRegistry operations (TS-301)
- [x] T208 Write unit tests for SubdomainGenerator uniqueness (TS-302)

### Client Core Infrastructure (Blocks: US1, US2)

- [x] T301 Create ClientConfig data class for CLI arguments (server-url, secret-key, local-url, subdomain)
- [x] T302 Implement configuration parsing with validation (for TS-303)
- [x] T303 Write unit tests for ClientConfig parsing (TS-303)

---

## Phase 3: User Story 1 - Client Connection (P1)

**Objective**: Enable clients to connect, authenticate, and receive subdomains

**Acceptance Tests**: TS-001, TS-002, TS-003  
**Requirements**: FR-001, FR-002, FR-003  
**Depends On**: Phase 2 (T101-T108, T201-T208, T301-T303)

### Server Implementation

- [x] T401 Implement TunnelWebSocketEndpoint with @ServerEndpoint annotation
- [x] T402 Implement secret key validation on WebSocket connection open (for TS-002)
- [x] T403 Implement subdomain assignment and registration in registry (for TS-001)
- [x] T404 Send CONTROL message with REGISTERED action containing subdomain and publicUrl (for TS-105, TS-001)
- [x] T405 Implement connection error handling with proper close codes
- [x] T406 Handle client disconnect cleanup with 30-second grace period

### Client Implementation

- [x] T411 Create TunnelClient main class with CLI argument parsing
- [x] T412 Implement WebSocketClientEndpoint with connection logic
- [x] T413 Send authentication (secret key) on connection open
- [x] T414 Handle CONTROL/REGISTERED message to extract subdomain URL (for TS-001)
- [x] T415 Implement connection failure handling with clear error messages (for TS-003)

### Integration Tests

- [x] T421 Write integration test: valid secret key connection receives subdomain (TS-001)
- [x] T422 Write integration test: invalid secret key rejected with 401 (TS-002)
- [x] T423 Write integration test: server unreachable error handling (TS-003)

---

## Phase 4: User Story 2 - HTTP Request Forwarding (P1)

**Objective**: Forward HTTP requests from public subdomains to local applications

**Acceptance Tests**: TS-004, TS-005, TS-006  
**Requirements**: FR-004, FR-005, FR-006  
**Depends On**: Phase 3 (US1 complete)

### Server Implementation

- [x] T501 Implement SubdomainRoutingHandler as Vert.x Route handler
- [x] T502 Extract subdomain from Host header and lookup in TunnelRegistry
- [x] T503 Return 404 for unknown subdomains
- [x] T504 Implement RequestForwarder to serialize HTTP requests into WebSocket messages
- [x] T505 Handle RESPONSE messages and stream back to original HTTP requester
- [x] T506 Implement request timeout handling (30s) returning 504 (for TS-205)
- [x] T507 Handle client disconnection during request (return 503) (for TS-202)

### Client Implementation

- [x] T511 Implement LocalHttpProxy to make HTTP requests to local application
- [x] T512 Handle incoming REQUEST messages from WebSocket
- [x] T513 Forward request to local application preserving method, headers, body
- [x] T514 Serialize local application response into RESPONSE message with correlationId
- [x] T515 Handle local application unreachable (return error response) (for TS-006)

### Integration Tests

- [x] T521 Write integration test: HTTP GET forwarding end-to-end (TS-004)
- [x] T522 Write integration test: POST with body forwarding (TS-005)
- [x] T523 Write integration test: local app unavailable returns 502/503 (TS-006)
- [x] T524 Write integration test: request timeout returns 504 (TS-205)
- [x] T525 Write integration test: client disconnect during request (TS-202)

---

## Phase 5: User Story 3 - Multiple Concurrent Tunnels (P2)

**Objective**: Support multiple simultaneous client connections with proper routing

**Acceptance Tests**: TS-007, TS-008, TS-009  
**Requirements**: FR-007  
**Depends On**: Phase 4 (US2 complete)

### Server Implementation

- [x] T601 Implement concurrent connection handling in TunnelWebSocketEndpoint
- [x] T602 Implement subdomain collision detection and regeneration (for TS-203)
- [x] T603 Ensure thread-safe tunnel registry operations under concurrent load
- [x] T604 Validate correct routing when multiple tunnels are active (for TS-008)

### Integration Tests

- [x] T611 Write integration test: multiple clients connect simultaneously (TS-007)
- [x] T612 Write integration test: requests route to correct tunnel (TS-008)
- [x] T613 Write integration test: same secret key creates independent tunnels (TS-009)
- [x] T614 Write integration test: subdomain collision handling (TS-203)
- [x] T615 Write load test: 100 concurrent tunnels, 1000 req/s (SC-003)

---

## Phase 6: User Story 4 - Configuration Management (P3)

**Objective**: Enable configurable base domain and authentication policies

**Acceptance Tests**: TS-010, TS-011  
**Requirements**: FR-008  
**Depends On**: Phase 2 (T201-T208)

### Server Implementation

- [x] T701 Implement RelayConfig loading from application.yml and environment variables
- [x] T702 Implement base domain configuration for subdomain generation (for TS-010)
- [x] T703 Implement secret key validation rules configuration (for TS-011)
- [x] T704 Add configuration validation on server startup

### Integration Tests

- [x] T711 Write integration test: custom base domain configuration (TS-010)
- [x] T712 Write integration test: secret key validation rules (TS-011)

---

## Phase 7: User Story 5 - WebSocket Forwarding (P2)

**Objective**: Support WebSocket connections from external users through the tunnel

**Acceptance Tests**: TS-012, TS-013, TS-014  
**Requirements**: FR-010  
**Depends On**: Phase 4 (US2 complete)

### Server Implementation

- [x] T801 Detect WebSocket upgrade requests in SubdomainRoutingHandler
- [x] T802 Implement WebSocket-over-WebSocket proxying for external connections
- [x] T803 Forward WebSocket messages bidirectionally (external ↔ client)
- [x] T804 Handle WebSocket connection close from either side
- [x] T805 Implement invalid WebSocket message handling (close with 1008) (for TS-207)

### Client Implementation

- [x] T811 Handle WebSocket REQUEST messages from server
- [x] T812 Establish local WebSocket connection to target application
- [x] T813 Proxy messages between server and local WebSocket
- [x] T814 Handle local WebSocket connection errors

### Integration Tests

- [x] T821 Write integration test: WebSocket upgrade forwarding (TS-012)
- [x] T822 Write integration test: external → local WebSocket message (TS-013)
- [x] T823 Write integration test: local → external WebSocket message (TS-014)
- [x] T824 Write integration test: invalid WebSocket message handling (TS-207)

---

## Phase 8: Resilience Features (P2)

**Objective**: Implement graceful shutdown and reconnection capabilities

**Acceptance Tests**: TS-015, TS-016, TS-017  
**Requirements**: FR-011, FR-012  
**Depends On**: Phase 4 (US2 complete)

### Server Implementation

- [x] T901 Implement configurable shutdown behavior (graceful/immediate)
- [x] T902 Implement graceful shutdown: wait for in-flight requests (30s timeout) (for TS-015)
- [x] T903 Implement immediate shutdown: close all connections immediately (for TS-016)
- [x] T904 Notify clients of impending shutdown via CONTROL message
- [x] T905 Reject new connections during shutdown sequence

### Client Implementation

- [x] T911 Implement ReconnectionHandler with exponential backoff
- [x] T912 Implement 1s initial delay, doubling each retry (2s, 4s, 8s...) (for TS-017)
- [x] T913 Implement 60-second delay cap with infinite retries (for TS-017)
- [x] T914 Add jitter to reconnection delays to prevent thundering herd
- [x] T915 Handle server shutdown notification gracefully

### Integration Tests

- [x] T921 Write integration test: graceful shutdown with in-flight requests (TS-015)
- [x] T922 Write integration test: immediate shutdown behavior (TS-016)
- [x] T923 Write integration test: client exponential backoff reconnection (TS-017)

---

## Phase 9: Edge Cases & Advanced Integration

**Objective**: Handle large bodies, non-HTTP responses, and resource limits

**Acceptance Tests**: TS-204, TS-206, TS-208  
**Depends On**: Phase 4 (US2 complete)

### Implementation

- [x] T1001 Implement 10MB body size limit with 413 response (for TS-204)
- [x] T1002 Implement base64 encoding/decoding for large request/response bodies
- [x] T1003 Handle non-HTTP response from local app (return 502) (for TS-206)
- [x] T1004 Implement resource limit monitoring
- [x] T1005 Reject new connections when resources exhausted (503) (for TS-208)

### Integration Tests

- [x] T1011 Write integration test: large body handling (5MB) (TS-204)
- [x] T1012 Write integration test: non-HTTP local response (TS-206)
- [x] T1013 Write integration test: resource exhaustion rejection (TS-208)

---

## Phase 10: Observability & Polish

**Objective**: Add metrics, logging, and documentation

**Requirements**: Constitution Principle V (Observability by Design)

### Implementation

- [x] T1101 Configure Micrometer metrics: relay.tunnels.active gauge
- [x] T1102 Configure Micrometer metrics: relay.requests.total counter
- [x] T1103 Configure Micrometer metrics: relay.requests.duration timer
- [x] T1104 Configure Micrometer metrics: relay.requests.errors counter
- [x] T1105 Implement structured JSON logging with correlation IDs
- [x] T1106 Add health check endpoints (/health/live, /health/ready)
- [x] T1107 Create README with setup and usage instructions
- [x] T1108 Create deployment guide with Docker example

---

## Dependency Graph

```
Phase 1 (Setup)
    │
    ▼
Phase 2 (Foundational)
    ├── Shared Protocol (T101-T108)
    ├── Server Core (T201-T208)
    └── Client Core (T301-T303)
         │
         ├──► Phase 3 (US1 - Connection)
         │       │
         │       └──► Phase 4 (US2 - HTTP Forwarding)
         │               │
         │               ├──► Phase 5 (US3 - Concurrent Tunnels)
         │               │
         │               ├──► Phase 7 (US5 - WebSocket) [if US2 done]
         │               │
         │               ├──► Phase 8 (Resilience) [if US2 done]
         │               │
         │               └──► Phase 9 (Edge Cases) [if US2 done]
         │
         └──► Phase 6 (US4 - Config) [can run in parallel with Phase 3+]

Phase 10 (Observability) [depends on all above]
```

---

## Parallel Execution Opportunities

### Within Phase 1
- T003, T004, T005 can be done in parallel (module configurations)

### Within Phase 2
- T101-T108 (protocol) can be done in parallel with T201-T204 (server core)
- T301-T303 (client config) can be done in parallel with above

### Within Phase 3 (US1)
- T401-T406 (server) can be done in parallel with T411-T415 (client)

### Phase 5, 7, 8, 9 After Phase 4
Once US2 (HTTP forwarding) is complete, these phases can proceed in any order or parallel:
- Phase 5: Concurrent tunnels
- Phase 7: WebSocket forwarding
- Phase 8: Resilience features
- Phase 9: Edge cases

---

## MVP Scope (Recommended)

**Minimum Viable Product**: Complete Phase 1 through Phase 4

This delivers:
- ✅ Client connection with authentication (US1)
- ✅ HTTP request forwarding (US2)
- ✅ Basic error handling (502, 503, 504)
- ⬜ Concurrent tunnels (US3 - P2)
- ⬜ Configuration management (US4 - P3)
- ⬜ WebSocket forwarding (US5 - P2)
- ⬜ Resilience features (shutdown, reconnection)

---

## Test Traceability Matrix

| Test ID | Description | Covered By Tasks |
|---------|-------------|------------------|
| TS-001 | Client connection valid key | T401-T406, T411-T414 |
| TS-002 | Client connection invalid key | T402, T422 |
| TS-003 | Server unreachable | T415, T423 |
| TS-004 | HTTP GET forwarding | T501-T506, T511-T514, T521 |
| TS-005 | POST with body forwarding | T504, T513, T522 |
| TS-006 | Local app unavailable | T515, T523 |
| TS-007 | Multiple concurrent tunnels | T601-T604, T611 |
| TS-008 | Request routing to correct client | T602, T612 |
| TS-009 | Same secret key, independent tunnels | T602, T613 |
| TS-010 | Server domain configuration | T702, T711 |
| TS-011 | Secret key validation config | T703, T712 |
| TS-012 | WebSocket upgrade | T801, T821 |
| TS-013 | WebSocket external→local | T803, T822 |
| TS-014 | WebSocket local→external | T803, T823 |
| TS-015 | Graceful shutdown | T901-T905, T921 |
| TS-016 | Immediate shutdown | T903, T922 |
| TS-017 | Exponential backoff reconnection | T911-T914, T923 |
| TS-101-106 | Contract tests | T101-T108 |
| TS-201 | E2E forwarding | T521 |
| TS-202 | Client disconnection | T406, T507, T525 |
| TS-203 | Subdomain collision | T602, T614 |
| TS-204 | Large body handling | T1001-T1002, T1011 |
| TS-205 | Request timeout | T506, T524 |
| TS-206 | Non-HTTP response | T1003, T1012 |
| TS-207 | Invalid WebSocket message | T805, T824 |
| TS-208 | Resource exhaustion | T1004-T1005, T1013 |
| TS-301 | TunnelRegistry | T204, T207 |
| TS-302 | Subdomain generation | T203, T208 |
| TS-303 | Configuration parsing | T205, T302, T303, T208, T712 |

---

## Summary

| Metric | Count |
|--------|-------|
| Total Tasks | 113 |
| Setup Phase | 7 |
| Foundational Phase | 20 |
| User Story 1 (P1) | 13 |
| User Story 2 (P1) | 15 |
| User Story 3 (P2) | 9 |
| User Story 4 (P3) | 6 |
| User Story 5 (P2) | 12 |
| Resilience (P2) | 13 |
| Edge Cases | 9 |
| Observability | 8 |

**MVP Task Count**: 62 tasks (Phase 1-4)  
**Full Implementation**: 113 tasks

---

**Next Steps**:
1. `/iikit-07-analyze` - Validate cross-artifact consistency
2. `/iikit-08-implement` - Execute implementation (requires 100% checklist completion)

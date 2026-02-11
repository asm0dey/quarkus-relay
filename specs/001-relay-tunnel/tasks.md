# Tasks: Relay Tunnel Service

**Input**: Design documents from `/specs/001-relay-tunnel/`
**Prerequisites**: plan.md, spec.md, contracts/websocket-protocol.md

**Tests**: TDD is mandatory per Constitution. Test tasks included.

**Organization**: Tasks grouped by phase and user story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Multi-module Gradle project structure with Quarkus dependencies

### Project Structure

- [ ] T001 Create `server/` module with `build.gradle.kts` - Quarkus server dependencies (websockets, vertx, config-yaml, health, micrometer)
- [ ] T002 Create `client/` module with `build.gradle.kts` - Quarkus client dependencies (websockets, config, picocli for CLI)
- [ ] T003 [P] Create `shared/` module with message protocol classes
- [ ] T004 Update root `settings.gradle.kts` to include server, client, shared modules
- [ ] T005 Update root `build.gradle.kts` with common configuration (Kotlin version, Java 21 target)

**Checkpoint**: Gradle build succeeds with all modules configured

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before user stories

**CRITICAL**: No user story work can begin until this phase is complete

### Shared Protocol (shared module)

- [ ] T006 Create shared message types in `shared/src/main/kotlin/org/relay/shared/protocol/MessageType.kt` - enum class REQUEST, RESPONSE, ERROR, CONTROL
- [ ] T007 [P] Create `shared/src/main/kotlin/org/relay/shared/protocol/Envelope.kt` - data class with correlationId, type, timestamp, payload
- [ ] T008 [P] Create `shared/src/main/kotlin/org/relay/shared/protocol/RequestPayload.kt` - method, path, query, headers, body
- [ ] T009 [P] Create `shared/src/main/kotlin/org/relay/shared/protocol/ResponsePayload.kt` - statusCode, headers, body
- [ ] T010 Create `shared/src/main/kotlin/org/relay/shared/protocol/ControlPayload.kt` - action, subdomain, publicUrl, reason
- [ ] T011 Create `shared/src/main/kotlin/org/relay/shared/protocol/ErrorPayload.kt` - code, message, details
- [ ] T012 Create JSON serialization/deserialization utilities for envelope types

### Server Foundation (server module)

- [ ] T013 Create `server/src/main/kotlin/org/relay/server/config/RelayConfig.kt` - Configuration class with domain, secret-keys, timeout settings
- [ ] T014 Create `server/src/main/resources/application.yml` - Default configuration
- [ ] T015 Create `server/src/main/kotlin/org/relay/server/tunnel/TunnelConnection.kt` - Data class with subdomain, session, createdAt
- [ ] T016 Create `server/src/main/kotlin/org/relay/server/tunnel/TunnelRegistry.kt` - Thread-safe registry with register/unregister/getBySubdomain
- [ ] T017 Create `server/src/main/kotlin/org/relay/server/tunnel/SubdomainGenerator.kt` - 12-char alphanumeric random generator with collision detection

### Client Foundation (client module)

- [ ] T018 Create `client/src/main/kotlin/org/relay/client/config/ClientConfig.kt` - Configuration for server-url, secret-key, local-url, subdomain
- [ ] T019 Create `client/src/main/kotlin/org/relay/client/TunnelClient.kt` - Main CLI entry point with Picocli argument parsing

**Checkpoint**: Foundation ready - shared protocol classes build, server and client configs compile

---

## Phase 3: User Story 1 - Client Connection (Priority: P1) MVP

**Goal**: Client can connect to server with secret key and receive assigned subdomain

**Independent Test**: Server running, client connects with valid key → receives subdomain confirmation

### Tests for User Story 1 (Write these FIRST)

- [ ] T020 [P] [US1] Unit test: `SubdomainGenerator` produces unique 12-char alphanumeric strings in `server/src/test/kotlin/org/relay/server/tunnel/SubdomainGeneratorTest.kt`
- [ ] T021 [P] [US1] Unit test: `TunnelRegistry` thread-safe register/unregister/get operations in `server/src/test/kotlin/org/relay/server/tunnel/TunnelRegistryTest.kt`
- [ ] T022 [P] [US1] Unit test: Secret key validation logic in `server/src/test/kotlin/org/relay/server/auth/SecretKeyValidatorTest.kt`
- [ ] T023 [US1] Integration test: Client connects with valid key → receives subdomain in `server/src/test/kotlin/org/relay/server/TunnelConnectionTest.kt`
- [ ] T024 [US1] Integration test: Client connects with invalid key → connection rejected in `server/src/test/kotlin/org/relay/server/TunnelConnectionTest.kt`

### Implementation for User Story 1

- [ ] T025 [P] [US1] Create `server/src/main/kotlin/org/relay/server/websocket/TunnelWebSocketEndpoint.kt` - @ServerEndpoint with secret key header validation
- [ ] T026 [P] [US1] Implement secret key validation in WebSocket endpoint - reject with 401 if invalid
- [ ] T027 [US1] Implement subdomain assignment in WebSocket `@OnOpen` - generate, register, send CONTROL REGISTERED message
- [ ] T028 [US1] Implement WebSocket `@OnClose` and `@OnError` - unregister tunnel from registry
- [ ] T029 [US1] Create `client/src/main/kotlin/org/relay/client/websocket/WebSocketClientEndpoint.kt` - @ClientEndpoint with connection handling
- [ ] T030 [US1] Implement client connection logic with secret key header in `WebSocketClientEndpoint`
- [ ] T031 [US1] Handle CONTROL REGISTERED message - store subdomain, print public URL
- [ ] T032 [US1] Handle connection errors and display user-friendly messages in client
- [ ] T033 [US1] Add structured logging for connection events (SLF4J with JSON format)

**Checkpoint**: US1 complete - client can connect, authenticate, receive subdomain; tests pass

---

## Phase 4: User Story 2 - HTTP Request Forwarding (Priority: P1) MVP

**Goal**: HTTP requests to subdomain are forwarded to local application

**Independent Test**: Request to public subdomain → reaches local app → response returned

### Tests for User Story 2 (Write these FIRST)

- [ ] T034 [P] [US2] Unit test: Request serialization to envelope in `shared/src/test/kotlin/org/relay/shared/protocol/SerializationTest.kt`
- [ ] T035 [P] [US2] Unit test: Response deserialization from envelope in `shared/src/test/kotlin/org/relay/shared/protocol/SerializationTest.kt`
- [ ] T036 [US2] Integration test: GET request forwarding with response in `server/src/test/kotlin/org/relay/server/RequestForwardingTest.kt`
- [ ] T037 [US2] Integration test: POST with JSON body forwarding in `server/src/test/kotlin/org/relay/server/RequestForwardingTest.kt`
- [ ] T038 [US2] Integration test: Local app unavailable → 502/504 error response in `server/src/test/kotlin/org/relay/server/RequestForwardingTest.kt`

### Implementation for User Story 2

- [ ] T039 [P] [US2] Create `server/src/main/kotlin/org/relay/server/routing/SubdomainRoutingHandler.kt` - Vert.x route handler extracting subdomain from Host header
- [ ] T040 [P] [US2] Implement subdomain lookup in routing handler - 404 if not found
- [ ] T041 [US2] Create `server/src/main/kotlin/org/relay/server/forwarder/RequestForwarder.kt` - Manages pending requests with correlation IDs
- [ ] T042 [US2] Implement HTTP request serialization to REQUEST envelope in `RequestForwarder`
- [ ] T043 [US2] Implement REQUEST message sending via WebSocket in `RequestForwarder`
- [ ] T044 [US2] Implement timeout handling (30s default) for pending requests
- [ ] T045 [US2] Implement RESPONSE message handling - correlate and complete future
- [ ] T046 [US2] Implement response streaming to original requester in `SubdomainRoutingHandler`
- [ ] T047 [US2] Create `client/src/main/kotlin/org/relay/client/proxy/LocalHttpProxy.kt` - Makes HTTP requests to local app
- [ ] T048 [US2] Implement REQUEST message handling in `WebSocketClientEndpoint` - deserialize and call LocalHttpProxy
- [ ] T049 [US2] Implement local HTTP request execution with Vert.x HttpClient in `LocalHttpProxy`
- [ ] T050 [US2] Implement response serialization to RESPONSE envelope in `LocalHttpProxy`
- [ ] T051 [US2] Handle local app connection errors - send ERROR message to server
- [ ] T052 [US2] Implement body size limits (10MB) - return 413 if exceeded

**Checkpoint**: US2 complete - full request forwarding works; tests pass

---

## Phase 5: User Story 3 - Multiple Concurrent Tunnels (Priority: P2)

**Goal**: Multiple clients can connect simultaneously with unique subdomains

**Independent Test**: 3 clients connect → each gets unique subdomain → requests route correctly

### Tests for User Story 3 (Write these FIRST)

- [ ] T053 [P] [US3] Integration test: 3 concurrent tunnels, each with unique subdomain in `server/src/test/kotlin/org/relay/server/ConcurrentTunnelsTest.kt`
- [ ] T054 [US3] Integration test: Requests to different subdomains route to correct clients in `server/src/test/kotlin/org/relay/server/ConcurrentTunnelsTest.kt`
- [ ] T055 [US3] Integration test: Duplicate secret key handling - second connection behavior in `server/src/test/kotlin/org/relay/server/ConcurrentTunnelsTest.kt`

### Implementation for User Story 3

- [ ] T056 [P] [US3] Verify `TunnelRegistry` handles concurrent access correctly (already implemented, verify with tests)
- [ ] T057 [US3] Define behavior for duplicate secret key connections - replace existing or reject new
- [ ] T058 [US3] Implement duplicate key handling in `TunnelWebSocketEndpoint` (replace: close old, accept new)
- [ ] T059 [US3] Add metrics: `relay.tunnels.active` gauge in `TunnelRegistry`
- [ ] T060 [US3] Add metrics: `relay.requests.total` counter in `RequestForwarder`
- [ ] T061 [US3] Add metrics: `relay.requests.duration` timer in `RequestForwarder`
- [ ] T062 [US3] Add metrics: `relay.requests.errors` counter in error handlers

**Checkpoint**: US3 complete - multiple concurrent tunnels work; metrics available

---

## Phase 6: User Story 4 - Configuration (Priority: P3)

**Goal**: Server configurable via YAML/env vars; client via CLI args

**Independent Test**: Custom config values → server/client use them correctly

### Tests for User Story 4 (Write these FIRST)

- [ ] T063 [P] [US4] Unit test: Configuration loading from YAML and env vars in `server/src/test/kotlin/org/relay/server/config/RelayConfigTest.kt`
- [ ] T064 [US4] Unit test: Client CLI argument parsing in `client/src/test/kotlin/org/relay/client/config/ClientConfigTest.kt`

### Implementation for User Story 4

- [ ] T065 [P] [US4] Implement configuration mapping for `relay.domain` - used in subdomain generation
- [ ] T066 [P] [US4] Implement `relay.secret-keys` comma-separated list validation
- [ ] T067 [P] [US4] Implement `relay.request-timeout` configuration (default 30s)
- [ ] T068 [P] [US4] Implement `relay.max-body-size` configuration (default 10MB)
- [ ] T069 [US4] Implement `relay.subdomain-length` configuration (default 12)
- [ ] T070 [US4] Add CLI option `--subdomain` for requesting specific subdomain in `TunnelClient`
- [ ] T071 [US4] Handle requested subdomain conflicts - generate alternative if taken

**Checkpoint**: US4 complete - configuration works for all settings

---

## Phase 7: Edge Cases & Robustness

**Purpose**: Handle edge cases identified in spec

### Tests for Edge Cases (Write these FIRST)

- [ ] T072 [P] [EDGE] Integration test: Client disconnects mid-request - in-flight request handling in `server/src/test/kotlin/org/relay/server/EdgeCaseTest.kt`
- [ ] T073 [P] [EDGE] Integration test: Large request body (5MB) forwarding in `server/src/test/kotlin/org/relay/server/EdgeCaseTest.kt`
- [ ] T074 [P] [EDGE] Unit test: Subdomain collision detection and retry logic in `server/src/test/kotlin/org/relay/server/tunnel/SubdomainGeneratorTest.kt`
- [ ] T075 [EDGE] Integration test: Malformed HTTP request handling in `server/src/test/kotlin/org/relay/server/EdgeCaseTest.kt`

### Implementation for Edge Cases

- [ ] T076 [P] [EDGE] Implement graceful client disconnection handling - complete in-flight requests with error
- [ ] T077 [P] [EDGE] Implement Base64 streaming for large bodies to avoid memory issues
- [ ] T078 [EDGE] Ensure subdomain collision detection retries until unique found
- [ ] T079 [EDGE] Implement malformed request handling - return 400 Bad Request
- [ ] T080 [EDGE] Implement rate limiting consideration (document for nginx/traefik config)

**Checkpoint**: Edge cases handled - robust error handling in place

---

## Phase 8: Client Resilience

**Purpose**: Auto-reconnect and error recovery on client side

### Tests for Resilience (Write these FIRST)

- [ ] T081 Unit test: Reconnection backoff strategy in `client/src/test/kotlin/org/relay/client/retry/ReconnectionHandlerTest.kt`
- [ ] T082 Integration test: Client reconnects after server restart in `client/src/test/kotlin/org/relay/client/ResilienceTest.kt`

### Implementation for Resilience

- [ ] T083 Create `client/src/main/kotlin/org/relay/client/retry/ReconnectionHandler.kt` - Exponential backoff logic
- [ ] T084 Implement auto-reconnect on connection failure in `WebSocketClientEndpoint`
- [ ] T085 Implement heartbeat/ping-pong to detect stale connections
- [ ] T086 Handle reconnection with previous subdomain request (if specified)

**Checkpoint**: Client resilient - auto-reconnects, handles failures gracefully

---

## Phase 9: Documentation

**Purpose**: User and operational documentation

- [ ] T087 Create `README.md` - Project overview, quick start guide
- [ ] T088 Create `docs/ARCHITECTURE.md` - System architecture and component interactions
- [ ] T089 Create `docs/DEPLOYMENT.md` - Server deployment instructions, DNS setup
- [ ] T090 Create `docs/CLIENT_USAGE.md` - Client CLI reference and examples
- [ ] T091 Create `docs/SECURITY.md` - Security considerations and best practices
- [ ] T092 Create `docker-compose.yml` - For local development and testing

**Checkpoint**: Documentation complete - users can deploy and use the system

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup) → Phase 2 (Foundation) → Phase 3-6 (User Stories) → Phase 7 (Edge Cases) → Phase 8 (Resilience) → Phase 9 (Docs)
                                    ↘ Phase 4 depends on Phase 3 completion (request forwarding needs connection)
```

### Critical Path

1. T001-T005: Project setup (can start immediately)
2. T006-T012: Shared protocol (blocks server/client foundation)
3. T013-T019: Server/client foundation (blocks all user stories)
4. T020-T033: US1 - Connection (blocks US2-4)
5. T034-T052: US2 - Request forwarding (core feature)
6. Remaining phases can largely proceed in parallel after US2

### Parallel Opportunities

- **Phase 1**: All setup tasks (T001-T005) can run in parallel
- **Phase 2**: Shared protocol (T006-T012) parallel with server foundation (T013-T017) and client foundation (T018-T019)
- **Phase 3 tests**: T020-T024 can run in parallel
- **Phase 4 tests**: T034-T038 can run in parallel
- **Within each user story**: Model/service/endpoint tasks marked [P] can run in parallel

---

## Summary

| Phase | Task Count | Description |
|-------|-----------|-------------|
| Phase 1: Setup | 5 | Multi-module Gradle project |
| Phase 2: Foundation | 14 | Shared protocol, server/client base |
| Phase 3: US1 - Connection | 14 | Authentication, subdomain assignment |
| Phase 4: US2 - Forwarding | 19 | Request/response routing |
| Phase 5: US3 - Concurrent | 10 | Multiple tunnels, metrics |
| Phase 6: US4 - Config | 7 | Configuration handling |
| Phase 7: Edge Cases | 8 | Robustness improvements |
| Phase 8: Resilience | 6 | Auto-reconnect, recovery |
| Phase 9: Documentation | 6 | User docs, deployment guides |
| **Total** | **89** | |

---

## Constitution Compliance

- **Test-First Verification**: Each user story and edge case phase begins with test tasks (marked "Write these FIRST")
- **Modularity**: Clear separation between server, client, and shared modules; interfaces defined in contracts/
- **Observability**: Metrics tasks (T059-T062) and structured logging (T033) included
- **Progressive Disclosure**: Phased implementation with checkpoints; US1+US2 deliver MVP

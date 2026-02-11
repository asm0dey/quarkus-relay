# WebSocket Protocol Contract

**Version**: 1.0.0  
**Purpose**: Define message format for client-server communication over WebSocket

## Overview

All messages exchanged between tunnel client and relay server use a JSON envelope format with correlation IDs for request/response matching.

## Envelope Format

```json
{
  "correlationId": "uuid-v4-string",
  "type": "REQUEST|RESPONSE|ERROR|CONTROL",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": { ... }
}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `correlationId` | String | Yes | UUID v4 for request/response correlation |
| `type` | Enum | Yes | Message type discriminator |
| `timestamp` | ISO8601 | Yes | Message creation timestamp |
| `payload` | Object | Yes | Type-specific payload (see below) |

## Message Types

### REQUEST (Server → Client)

Forwarded HTTP request from external user to local application.

```json
{
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "REQUEST",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "method": "POST",
    "path": "/api/users",
    "query": "page=1&limit=10",
    "headers": {
      "Content-Type": "application/json",
      "X-Forwarded-For": "203.0.113.42"
    },
    "body": "base64-encoded-request-body"
  }
}
```

**Payload Fields**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `method` | String | Yes | HTTP method (GET, POST, PUT, DELETE, etc.) |
| `path` | String | Yes | Request path (e.g., "/api/status") |
| `query` | String | No | Query string without leading "?" |
| `headers` | Object | Yes | Map of header names to values |
| `body` | String | No | Base64-encoded request body (empty string if no body) |

### RESPONSE (Client → Server)

HTTP response from local application to be returned to external user.

```json
{
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "RESPONSE",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "statusCode": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "base64-encoded-response-body"
  }
}
```

**Payload Fields**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `statusCode` | Integer | Yes | HTTP status code (200, 404, 500, etc.) |
| `headers` | Object | Yes | Map of header names to values |
| `body` | String | No | Base64-encoded response body |

### ERROR (Bidirectional)

Error indication for failed operations.

```json
{
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "ERROR",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "code": "TIMEOUT|UPSTREAM_ERROR|INVALID_REQUEST|SERVER_ERROR",
    "message": "Local application did not respond within timeout",
    "details": { }
  }
}
```

**Error Codes**:

| Code | Description |
|------|-------------|
| `TIMEOUT` | Request to local application timed out |
| `UPSTREAM_ERROR` | Local application returned error or was unreachable |
| `INVALID_REQUEST` | Malformed request received |
| `SERVER_ERROR` | Internal server error |
| `RATE_LIMITED` | Request rate limit exceeded |

### CONTROL (Bidirectional)

Connection management and status messages.

#### Tunnel Registration (Server → Client on connection)

```json
{
  "correlationId": "00000000-0000-0000-0000-000000000001",
  "type": "CONTROL",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "action": "REGISTERED",
    "subdomain": "abc123def456",
    "publicUrl": "https://abc123def456.tun.example.com"
  }
}
```

#### Heartbeat (Bidirectional)

```json
{
  "correlationId": "00000000-0000-0000-0000-000000000002",
  "type": "CONTROL",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "action": "PING"
  }
}
```

```json
{
  "correlationId": "00000000-0000-0000-0000-000000000002",
  "type": "CONTROL",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "action": "PONG"
  }
}
```

#### Disconnect Notice (Bidirectional)

```json
{
  "correlationId": "00000000-0000-0000-0000-000000000003",
  "type": "CONTROL",
  "timestamp": "2026-02-11T10:30:00Z",
  "payload": {
    "action": "DISCONNECT",
    "reason": "NEW_CONNECTION|SHUTDOWN|ERROR"
  }
}
```

## Connection Establishment

1. Client opens WebSocket connection to `wss://server.example.com/ws`
2. Client includes `X-Relay-Secret-Key` header with authentication token
3. Server validates secret key
4. Server assigns random subdomain and sends `CONTROL` message with `REGISTERED` action
5. Connection is ready for request forwarding

## Request Flow

```
External Request
      │
      ▼
┌─────────────┐
│ Relay Server│
└──────┬──────┘
       │ 1. Serialize to REQUEST message
       │ 2. Generate correlationId
       ▼
   WebSocket
       │
       ▼
┌─────────────┐
│Tunnel Client│
└──────┬──────┘
       │ 3. Deserialize
       │ 4. Make HTTP request to local app
       ▼
   Local App
       │
       ▼
   Response
       │
       ▼
┌─────────────┐
│Tunnel Client│
└──────┬──────┘
       │ 5. Serialize to RESPONSE message
       │ 6. Use same correlationId
       ▼
   WebSocket
       │
       ▼
┌─────────────┐
│ Relay Server│
└──────┬──────┘
       │ 7. Correlate with pending request
       │ 8. Stream response to external user
       ▼
   External User
```

## Timeout Handling

- Server waits for RESPONSE or ERROR for each REQUEST
- Default timeout: 30 seconds
- On timeout, server sends 504 Gateway Timeout to external user
- Client should respond with ERROR message if local app fails

## Body Encoding

- All HTTP bodies are Base64-encoded in JSON payloads
- Empty bodies are represented as empty string ""
- Maximum body size: 10MB (configurable)

## Correlation ID Requirements

- Must be UUID v4 format
- Client must echo server's correlationId in RESPONSE/ERROR
- Control messages may use sequential low IDs (e.g., "00000000-0000-0000-0000-000000000001")

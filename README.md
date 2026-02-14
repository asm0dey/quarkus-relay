# 📡 Relay - Simple HTTP Tunneling Service

Relay is a lightning-fast tunneling service that exposes your local HTTP services to the internet. Built with Quarkus and Kotlin, it's designed for developers who need a reliable way to demo local work, test webhooks, or share progress without complex network configuration.

[![GitHub Release](https://img.shields.io/github/v/release/asm0dey/relay)](https://github.com/asm0dey/relay/releases)
[![Docker Image](https://img.shields.io/badge/docker-ghcr.io-blue)](https://github.com/asm0dey/relay/pkgs/container/relay%2Fserver)

## 🚀 Quick Start

### 1. Get the Client
Download the latest pre-compiled binary for your platform from the [Releases Page](https://github.com/asm0dey/relay/releases).

Alternatively, if you have Java installed, you can use the JAR file or run via Docker.

### 2. Expose Your Local Service
Expose a local web server running on port `3000` using our public server (if available) or your own:

```bash
# Basic usage with a random subdomain
./relay-client 3000 --server tun.example.com --key your-secret-key
```

---

## 💻 Client CLI Usage

### Common Patterns

**Request a specific subdomain:**
```bash
relay-client 8080 -s tun.example.com -k my-key -d my-awesome-app
# → https://my-awesome-app.tun.example.com
```

**Quiet mode (useful for scripts):**
```bash
relay-client 3000 -s tun.example.com -k my-key --quiet
```

**Full options:**
```text
Usage: relay-client [OPTIONS] <port>

Arguments:
  <port>                 Local HTTP service port (1-65535)

Options:
  -s, --server=<host>    Tunnel server hostname
  -p, --server-port=<p>  Tunnel server port (default: 443)
  -k, --key=<secret>     Authentication secret key
  -d, --subdomain=<name> Request a specific subdomain
      --insecure         Use ws:// instead of wss://
  -v, --verbose          Enable debug logging
  -q, --quiet            Only show the tunnel URL
  -h, --help             Show this help message
```

### Configuration
You can avoid typing flags every time by creating a config file at `~/.relay/config.properties`:
```properties
relay.client.server-url=wss://tun.example.com/ws
relay.client.secret-key=your-secret-key
```
Then just run: `relay-client 3000`

---

## 🛠️ Server Deployment

The server is available as a multi-arch Docker image.

### Run with Docker
```bash
docker run -d \
  --name relay-server \
  -p 8080:8080 \
  -e RELAY_DOMAIN=tun.example.com \
  -e RELAY_SECRET_KEYS=key1,key2 \
  ghcr.io/asm0dey/relay/server:latest
```

### Configuration Variables
| Variable | Description | Default |
|----------|-------------|---------|
| `RELAY_DOMAIN` | Base domain for tunnels | `tun.example.com` |
| `RELAY_SECRET_KEYS` | Comma-separated allowed keys | (required) |
| `PORT` | Server HTTP port | `8080` |

---

## 🏗️ Development

### Prerequisites
* JDK 17+
* Docker (optional, for native builds)

### Build from source
```bash
./gradlew build
```

### Run in Dev Mode
```bash
./gradlew :server:quarkusDev  # Start server
./gradlew :client:quarkusDev  # Start client
```

## 📖 Documentation
* [Feature Specifications](specs/)
* [Architecture Overview](README.md#architecture)
* [Protocol Details](specs/001-relay-tunnel/contracts/websocket-protocol.md)

## 🛡️ License
This project is licensed under the MIT License - see the LICENSE file for details (if applicable).

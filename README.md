# ZIO HTTP Slack Socket Bot

A modern, type-safe Slack bot framework built with ZIO and zio-http, featuring thread-centric conversation management.

## Features

- **Socket Mode Support** - Real-time Slack connectivity without webhooks
- **Thread-Centric Design** - Bot responds only to direct @mentions, ignoring thread replies
- **AI-Powered Responses** - Multi-provider LLM support (Ollama, OpenAI, Anthropic, Azure)
- **Speculative Execution** - Immediate LLM start with automatic interruption on superseding messages
- **OpenTelemetry Observability** - Full distributed tracing, metrics, and dashboards
- **Type-Safe Domain** - Comprehensive Slack event modeling with zio-json
- **Reactive Architecture** - ZIO-based service layers with proper resource management
- **Per-Thread Queues** - Actor-like pattern for isolated thread processing
- **Crash Resilient** - Thread recovery from Slack API after bot restart

## Architecture

```
Slack WebSocket → SocketService → MessageProcessor → AI Bot → LLM Provider
                        ↓                  ↓              ↓
                  MessageStore → Per-Thread Queues → Response
                        ↓
                 OpenTelemetry Collector → Jaeger/Prometheus/Grafana
```

**Observability Stack:**
- OpenTelemetry Collector (central telemetry hub)
- Jaeger (distributed tracing)
- Prometheus (metrics)
- Grafana (dashboards)

See [docs/OTEL_COLLECTOR_STACK.md](docs/OTEL_COLLECTOR_STACK.md) for setup.

## Quick Start

### 1. Start Observability Stack (Recommended)
```bash
./scripts/start-observability.sh
# Starts OTel Collector, Jaeger, Prometheus, Grafana
```

### 2. Configure Slack App
Add your tokens to `application.conf`:
```hocon
app {
  slack-app-token = ${APP_SLACK_APP_TOKEN}
  slack-bot-token = ${APP_SLACK_BOT_TOKEN}
}
```

### 3. Run Bot
```bash
sbt dev  # Hot-reload development mode
```

### 4. View Telemetry
- **Traces**: http://localhost:16686 (Jaeger)
- **Metrics**: http://localhost:9090 (Prometheus)
- **Dashboards**: http://localhost:3000 (Grafana - admin/admin)

### 5. Test
Send `@bot` message in Slack, then rapidly send more messages to trigger LLM interruption!

## Design Philosophy

- **Selective Engagement** - Bot only acts on intentional @mentions, never thread noise
- **Speculative Execution** - Start LLM immediately, interrupt if superseded (token efficiency!)
- **Production Observability** - OpenTelemetry Collector pattern for vendor-neutral telemetry
- **Domain Purity** - Clean separation between Slack protocol and business logic  
- **ZIO Native** - Leverages ZIO's effect system for composable, testable services
- **Type Safety** - Compile-time guarantees for Slack event handling
- **Crash Resilient** - Stateless recovery using Slack as source of truth

Built for production-ready Slack bots that need reliable, maintainable conversation management.

## Development

### Commands
- `sbt compile` - Compile the code
- `sbt dev` - Run with hot-reload (recommended)
- `sbt run` - Run the application
- `sbt console` - Start a Scala 3 REPL

### Observability
- `./scripts/start-observability.sh` - Start OTel Collector stack (Podman)
- `./scripts/start-jaeger.sh` - Quick Jaeger-only setup (Podman)
- `podman-compose logs -f` - View all service logs

### Documentation
- [OpenTelemetry Setup](docs/OPENTELEMETRY_SETUP.md) - Quick reference
- [OTel Collector Stack](docs/OTEL_COLLECTOR_STACK.md) - Full observability setup
- [Phase 12 Summary](docs/PHASE_12_COMPLETE.md) - Latest features
- [Architecture](docs/MESSAGE_ARCHITECTURE.md) - System design
- [Zio-telemetry Guide](docs/Zio-telemetry.md) - Comprehensive telemetry guide

## License

MIT License - see [LICENSE](LICENSE) file for details.

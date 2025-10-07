# Copilot Instructions for fishy-zio-http-slackbot

**For ZIO-specific guidance and Zionomicon patterns, see `.github/prompts/functional.prompt.md`.**

**Package Structure**: All code uses `com.nestor10.slackbot` package (production-ready, reverse domain convention).

**Current Status**: MVP Complete - Production Deployment Phase (CI/CD setup in progress)

## Quick Reference

**Documentation** (always check first):
- `ARCHITECTURE.md` - Complete system architecture and design patterns
- `README.md` - Quick start and deployment guide

## Architecture Overview

This is a production-ready ZIO-based Slack bot implementing Socket Mode without Bolt. It features:
- **Event-Driven Architecture**: Domain events with pluggable processors
- **AI-Powered Responses**: Multi-provider LLM support (Ollama, OpenAI, Anthropic, Azure)
- **Per-Thread Processing**: Latest-wins pattern for optimal UX
- **Full Observability**: OpenTelemetry tracing + ZIO metrics
- **Resilient WebSocket**: Automatic reconnection with health monitoring

**Key Components:**
- `Main`: Application entry point, orchestrates config loading and service composition
- `SocketManager`: Multi-connection WebSocket pool with health monitoring
- `SocketService`: WebSocket lifecycle (handshake, frames, pings)
- `SlackApiClient`: HTTP client for Slack API (socket URLs, message posting)
- `MessageProcessorService`: Pure orchestration of Slack event processing
- `MessageStore`: In-memory persistence + domain event publishing (atomic)
- `MessageEventBus`: Hub-based broadcasting (Chapter 12 pattern)
- `ProcessorRegistry`: Worker fiber distributes events to processors (parallel, error-isolated)
- `MessageProcessor` (trait): Interface for pluggable processors with `canProcess()` filtering
- `AiBotProcessor`: AI bot with per-thread workers and latest-wins pattern
- `LLMService`: Multi-provider LLM client with configurable authentication
- `Thread` / `ThreadMessage`: Domain models for conversation management

**Data Flow:**
```
Slack WebSocket
  → SocketService (JSON parsing)
    → InboundQueue
      → MessageProcessorService (orchestration)
        → MessageStore (persistence + events)
          → MessageEventBus (broadcasting)
            → ProcessorRegistry Worker
              → [AiBot, Analytics, ...] (parallel, error-isolated)
```

**Architecture Principles** (Zionomicon):
- **Service Pattern** (Ch 17-18): trait + companion + Live + ZLayer
- **Domain Events** (Appendix 3): Events originate in MessageStore (where facts happen)
- **Hub Broadcasting** (Ch 12): Dumb broadcaster, smart subscribers (processors filter)
- **Resource Management** (Ch 15): Scoped resources, automatic cleanup
- **Error Isolation**: Processor failures don't affect event bus or other processors

## Key Patterns & Best Practices

### Service Pattern (Zionomicon Ch 17-18)
Always use: **trait + companion + Live implementation + ZLayer**

```scala
trait MyService {
  def doSomething: IO[Error, Result]
}

object MyService {
  case class Live(dependency: Dep) extends MyService {
    override def doSomething: IO[Error, Result] = ???
  }
  
  val layer: ZLayer[Dep, Nothing, MyService] = 
    ZLayer.fromFunction(Live.apply)
}
```

### Configuration (Ch 20)
- Use `ZIO.config(AppConfig.config)` after bootstrap sets config provider
- Bootstrap layer: `TypesafeConfigProvider.fromResourcePath().kebabCase.orElse(ConfigProvider.defaultProvider)`
- Env vars: `APP_SLACK_APP_TOKEN`, `APP_SLACK_BOT_TOKEN`, `APP_LLM_BASE_URL`, `APP_LLM_API_KEY`
- Config in `src/main/resources/application.conf` (HOCON format)

### Resource Management (Ch 15)
- Use `ZIO.scoped` for automatic resource cleanup
- All long-running fibers scoped (WebSocket connections, workers, subscriptions)
- Graceful shutdown: resources released on interruption

### Error Handling
- Map external errors to ZIO error types
- Use `tapError` for logging/metrics on failure
- Processor errors isolated via `forkDaemon` + `catchAll`

### JSON & HTTP
- **JSON**: Always use zio-json (`import zio.json._`)
- **HTTP Client**: zio-http Client (`client.request`, `body.fromJson`)
- **WebSocket**: zio-http WebSocketApp

### Observability
- **Tracing**: OpenTelemetry with `@@ Tracing.span(name)` aspect
- **Metrics**: ZIO Metric API (`Metric.histogram`, `Metric.gauge`, `Metric.counter`)
- **Logging**: Structured with FiberRef context (`LogContext.threadId(...)`)

## Developer Workflows

### Compilation
- **Metals MCP**: Use `mcp_fishy-zio-htt_compile-full` for fast feedback
- **sbt**: `sbt compile` (slower but standard)

### Development Mode
- **Hot Reload**: `sbt dev` (sbt-revolver) - auto-restart on source changes
- **Standard**: `sbt run`

### Code Quality
- **Format**: `sbt scalafmt` (auto-formats on compile)
- **Lint**: `sbt scalafix` (applies Scala 3 idioms)
- **Check**: `sbt scalafmtCheck` and `sbt "scalafixAll --check"` (CI)

### Debugging
- **Logs**: Configure logback.xml for Netty/zio-http logs
- **Fast Reconnects**: Set `APP_DEBUG_RECONNECTS=true` (360s reconnect window)
- **Tracing**: View spans in Jaeger UI (http://localhost:16686)

### Testing
- **Location**: `src/test/scala/`
- **Pattern**: Follow service pattern (mock dependencies via ZLayer)
- **Run**: `sbt test`

## Conventions

### Package Structure
- `com.nestor10.slackbot` - Root package
- `.application` - Orchestration (MessageProcessorService, SlackEventOrchestrator, ProcessorRegistry)
- `.domain` - Domain models and processors
  - `.conversation` - Thread, ThreadMessage
  - `.slack` - Slack event models
  - `.processor` - Processor implementations (AiBotProcessor)
  - `.event` - Domain events
- `.infrastructure` - External integrations
  - `.socket` - SocketManager, SocketService
  - `.http` - SlackApiClient
  - `.llm` - LLMService
  - `.storage` - MessageStore, MessageEventBus
- `.otel` - OpenTelemetry setup
- `.conf` - Configuration models

### Naming Conventions
- **Methods**: camelCase, descriptive (`requestSocketUrl`, not `openSocketConnection`)
- **Config fields**: kebab-case in HOCON, camelCase in Scala
- **Services**: Trait name matches file name (`SocketService.scala`)
- **Layers**: `layer` in companion object, `Live` for implementation

### Documentation
- **Code**: Scaladoc for public APIs (prefer over inline comments)
- **Architecture**: See `docs/ARCHITECTURE.md`
- **Guides**: See `docs/` for operational guides

## Integration Points

### Slack API
- **Socket URL**: POST `https://slack.com/api/apps.connections.open` with Bearer token
- **Error Handling**: Check `ok: false` with `error` field in response
- **Bot Token**: Used for posting messages (`xoxb-...`)
- **App Token**: Used for WebSocket connection (`xapp-...`)

### WebSocket Protocol
- **Connection**: Connect to URL from socket API
- **Handshake**: Handle `hello` message (contains connection metadata)
- **Disconnect**: Handle `disconnect` message (contains reason, reconnect after delay)
- **Pings**: Send every `pingIntervalSeconds` to keep connection alive

### LLM Providers
- **Ollama** (local): `baseUrl=http://localhost:11434`, no API key
- **OpenAI**: `baseUrl=https://api.openai.com/v1`, `apiKey=sk-...`
- **Anthropic**: `baseUrl=https://api.anthropic.com/v1`, `apiKey=sk-ant-...`
- **Azure**: Custom base URL, Azure API key
- **Selection**: Automatic based on presence of `APP_LLM_API_KEY`

### Observability Stack
- **Jaeger**: Tracing backend (http://localhost:16686)
- **Prometheus**: Metrics backend (http://localhost:9090)
- **Grafana**: Dashboards (http://localhost:3000)
- **OTLP Collector**: Telemetry pipeline (localhost:4317)

## Dependencies (build.sbt)

### Core ZIO
- `zio` 2.1.14
- `zio-streams` 2.1.14

### HTTP & WebSocket
- `zio-http` 3.0.1
- `zio-json` 0.7.3

### Configuration
- `zio-config` 4.0.2
- `zio-config-typesafe` 4.0.2
- `zio-config-magnolia` 4.0.2

### Observability
- `zio-opentelemetry` 3.1.10
- `zio-logging` 2.4.0
- `opentelemetry-sdk` 1.42.1
- `opentelemetry-exporter-otlp` 1.42.1

### Logging
- `logback-classic` 1.5.12

## Common Patterns

### Latest-Wins Pattern (Per-Thread Queues)
```scala
// Each thread gets sliding queue (capacity 1)
queue <- Queue.sliding[Instant](1)
// Latest message wins, older messages dropped
_ <- queue.offer(timestamp)
```

### Event Publishing (Atomic with Storage)
```scala
// In MessageStore
for {
  _ <- threads.update(_ + (thread.id -> thread))
  _ <- eventBus.publish(ThreadCreated(thread.id, ...))  // After success
} yield thread.id
```

### Processor Filtering (Smart Subscribers)
```scala
// In AiBotProcessor
override def canProcess(event: MessageEvent): Boolean = event match {
  case ThreadCreated(_, _, _) => true
  case MessageStored(_, _, author, source, _) => 
    source == MessageSource.Other  // Ignore bot's own messages
  case _ => false
}
```

### Aspect-Based Tracing
```scala
// Declarative instrumentation
myOperation
  @@ Tracing.span("operation_name")
  @@ LogContext.threadId(threadId)
```

## Next Steps (CI/CD)

The project is currently in CI/CD setup phase. See `TODO.md` for:
- GitHub Actions CI pipeline (build, test, format)
- Docker image publishing to Quay.io
- Deployment documentation

## Related Documentation
- `docs/ARCHITECTURE.md` - System architecture and design patterns
- `docs/ZIO_TELEMETRY_GUIDE.md` - OpenTelemetry implementation
- `docs/LLM_PROVIDERS.md` - LLM provider configuration
- `docs/OPENTELEMETRY_SETUP.md` - Quick observability reference
- `README.md` - Quick start and deployment guide



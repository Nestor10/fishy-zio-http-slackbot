# ZIO HTTP Slack Socket Bot

A modern, type-safe Slack bot framework built with ZIO and zio-http, featuring thread-centric conversation management.

## Features

- **Socket Mode Support** - Real-time Slack connectivity without webhooks
- **Thread-Centric Design** - Bot responds only to direct @mentions, ignoring thread replies
- **Type-Safe Domain** - Comprehensive Slack event modeling with zio-json
- **Reactive Architecture** - ZIO-based service layers with proper resource management
- **Precise Timestamps** - Native Double precision for Slack's microsecond timestamps

## Architecture

```
Slack WebSocket → SocketService → MessageProcessor → Thread Domain → Your Bot Logic
```

## Quick Start

1. Configure your Slack app token in `application.conf`
2. Run with `sbt dev` for hot-reload development
3. Bot creates new conversation threads on direct @mentions
4. Thread replies are automatically ignored per design

## Design Philosophy

- **Selective Engagement** - Bot only acts on intentional @mentions, never thread noise
- **Domain Purity** - Clean separation between Slack protocol and business logic  
- **ZIO Native** - Leverages ZIO's effect system for composable, testable services
- **Type Safety** - Compile-time guarantees for Slack event handling

Built for production-ready Slack bots that need reliable, maintainable conversation management.

## Development

This is a Scala 3 sbt project. You can:
- `sbt compile` - Compile the code
- `sbt dev` - Run with hot-reload (recommended for development)
- `sbt run` - Run the application
- `sbt console` - Start a Scala 3 REPL

## License

MIT License - see [LICENSE](LICENSE) file for details.

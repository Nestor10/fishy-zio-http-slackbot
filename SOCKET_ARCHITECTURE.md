# Socket Management Architecture

## Overview

This project implements a robust, scalable Slack Socket Mode client using ZIO and zio-http. The architecture follows functional programming principles with strong typing, composable effects, and comprehensive error handling.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    SlackSocketDemoApp                           │
│                   (Main Orchestrator)                          │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ZIO Streaming Pipeline                         │
│  Queue → Buffer → Parallel Processing → MessageProcessor       │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SocketManager                                │
│              (Connection Lifecycle)                             │
└─────────────────┬───────────────────────────────────────────────┘
                  │
       ┌──────────┼──────────┐
       ▼          ▼          ▼
┌─────────────┐ ┌─────────┐ ┌──────────────┐
│SlackApiClient│ │SocketService│ │MessageProcessor│
│   (URL Req) │ │(WebSocket)│ │ (Event Logic)│
└─────────────┘ └─────────┘ └──────────────┘
```

## Core Components

### 1. SlackSocketDemoApp (Main Entry Point)
**Location**: `src/main/scala/slacksocket/demo/SlackSocketDemoApp.scala`

**Responsibilities**:
- Application bootstrap and configuration
- ZIO streaming pipeline orchestration
- Service layer composition via ZLayers
- Resource lifecycle management with `ZIO.scoped`

**Key Features**:
- **ZStream Pipeline**: Implements pull-based streaming with buffering and parallel processing
- **Configuration Management**: Uses TypesafeConfig with environment variable fallbacks
- **Graceful Resource Management**: All resources cleaned up automatically via ZIO scopes

### 2. SocketManager (Connection Pool Manager)
**Location**: `src/main/scala/slacksocket/demo/service/SocketManager.scala`

**Responsibilities**:
- Manage multiple WebSocket connections to Slack
- Health monitoring and automatic reconnection
- Connection state tracking and cleanup
- Error categorization and recovery

**Architecture Pattern**: Service trait + Live implementation + ZLayer

**Key Features**:
- **Multi-Connection Support**: Configurable number of concurrent socket connections
- **Health Monitoring**: Tracks ping/pong cycles and connection timeouts  
- **Automatic Recovery**: Detects and replaces unhealthy connections
- **Fiber Management**: Tracks individual connection fibers for proper cleanup
- **Error Categorization**: DNS, network, timeout, and application error classification

**State Management**:
```scala
connectionStatesRef: Ref[Map[SocketId, SocketConnectionState]]
connectionFibersRef: Ref[Map[SocketId, Fiber[Throwable, Unit]]]
```

**Management Loop**:
1. **Health Check**: Evaluate connection health every 15 seconds
2. **Cleanup**: Interrupt and remove unhealthy connections
3. **Scale Up**: Start new connections to maintain target count
4. **Status Logging**: Report current connection states

### 3. SocketService (WebSocket Implementation)
**Location**: `src/main/scala/slacksocket/demo/service/SocketService.scala`

**Responsibilities**:
- Individual WebSocket connection management
- Slack protocol message handling (hello, disconnect, events)
- Ping/pong health monitoring
- Message acknowledgment

**Key Features**:
- **Protocol Compliance**: Handles Slack Socket Mode handshake and lifecycle
- **Auto-Acknowledgment**: Automatically acks EventsAPI messages
- **Ping Management**: Configurable ping intervals with background fiber
- **Scope Integration**: Proper resource cleanup via ZIO.scoped

**Message Flow**:
```
WebSocket Frame → Parse → Route → Queue/Acknowledge → Process
```

### 4. SlackApiClient (HTTP API Client)
**Location**: `src/main/scala/slacksocket/demo/service/SlackApiClient.scala`

**Responsibilities**:
- Request WebSocket URLs from Slack's `apps.connections.open` API
- HTTP client configuration and error handling
- Bearer token authentication

**Key Features**:
- **Error Mapping**: Converts Slack API errors to application errors
- **Type Safety**: Strongly-typed JSON parsing with zio-json
- **Configuration**: Debug reconnect support via URL parameters

### 5. MessageProcessor (Business Logic)
**Location**: `src/main/scala/slacksocket/demo/service/MessageProcessor.scala`

**Responsibilities**:
- Process incoming Slack events with type-safe pattern matching
- Route different event types to appropriate handlers
- Implement business logic for Slack interactions

**Supported Event Types**:
- **App Mentions** (`@bot`): Direct bot interactions
- **Messages**: Channel, DM, group, and thread messages
- **Reactions**: Emoji reactions for engagement tracking
- **Channel Events**: Creation, archiving, renaming
- **Team Events**: Member joining, user changes
- **Interactive Elements**: Buttons, modals, slash commands

## Data Flow Architecture

### 1. Connection Establishment
```
Config Load → SlackApiClient.requestSocketUrl() → SocketService.connect() → WebSocket Handshake
```

### 2. Message Processing Pipeline
```
Slack Event → WebSocket Frame → JSON Parse → Business Queue → ZStream → MessageProcessor → Business Logic
```

### 3. Health Monitoring
```
Ping Timer → WebSocket Ping → Pong Response → State Update → Health Evaluation
```

## Domain Model

### Core Types
```scala
// Connection Identity
case class SocketId(value: String)

// Connection Health
case class SocketConnectionState(
  isDisconnected: Boolean,
  isWarned: Boolean, 
  connectedAt: Option[Instant],
  approximateConnectionTime: Option[Duration],
  lastPongReceivedAt: Option[Instant]
)

// Message Queue
type InboundQueue = Queue[BusinessMessage]
```

### Event Hierarchy
```scala
sealed trait SlackSocketMessage
├── Hello (connection established)
├── Disconnect (connection closing)
└── EventsApiMessage (business events)
    └── payload.event: SlackEvent
        ├── AppMention
        ├── Message (unified for all message types)
        ├── ReactionAdded/ReactionRemoved
        ├── ChannelCreated/ChannelArchive/ChannelRename
        ├── TeamJoin/UserChange
        └── UnknownEvent (fallback)
```

## Configuration Management

**Config Sources** (priority order):
1. Environment variables (`APP_SLACK_APP_TOKEN`, `APP_PING_INTERVAL_SECONDS`)
2. System properties
3. `application.conf` (HOCON format)
4. Reference configuration

**Key Settings**:
```hocon
app {
  slack-app-token = ${APP_SLACK_APP_TOKEN}
  socket-count = 1
  ping-interval-seconds = 30
  debug-reconnects = false
}
```

## Error Handling Strategy

### Error Categorization
- **NETWORK_DNS**: Hostname resolution failures
- **NETWORK_CONNECTION**: TCP connection issues  
- **NETWORK_TIMEOUT**: Request/response timeouts
- **NETWORK_IO**: General I/O errors
- **APPLICATION**: Business logic errors
- **UNKNOWN**: Unclassified errors

### Recovery Mechanisms
1. **Connection-Level**: Individual socket reconnection
2. **Manager-Level**: Health monitoring and replacement
3. **Application-Level**: Graceful degradation and logging

### Observability
- **Structured Logging**: Categorized error messages with context
- **Health Metrics**: Connection state and timing information  
- **Debug Support**: Enhanced logging and reconnect parameters

## Concurrency & Streaming

### ZIO Streaming Architecture
```scala
ZStream.fromQueue(inboundQueue)
  .buffer(32)                    // Chunk efficiency
  .mapZIOParUnordered(4)        // Parallel processing
  .buffer(16)                   // Output buffering  
  .runDrain                     // Consume all
```

**Benefits**:
- **Pull-Based**: No back-pressure issues
- **Chunked Processing**: Efficient batch operations
- **Parallel Execution**: Concurrent message handling
- **Resource Safety**: Automatic fiber cleanup

### Fiber Management
- **Management Fiber**: Long-running health monitoring loop
- **Connection Fibers**: Individual WebSocket connections
- **Processing Fibers**: Parallel message processing
- **Ping Fibers**: Per-connection health pings

## Service Pattern

All services follow the consistent ZIO service pattern:

```scala
trait ServiceName:
  def operation(): ZIO[Env, Error, Result]

object ServiceName:
  object Live:
    case class Live(dependencies...) extends ServiceName
    val layer: ZLayer[Dependencies, Error, ServiceName] = ...
    
  object Stub:  // For testing
    val layer: ZLayer[Dependencies, Nothing, ServiceName] = ...
```

**Benefits**:
- **Testability**: Easy to mock with Stub implementations
- **Composability**: ZLayer automatic dependency injection
- **Type Safety**: Compile-time dependency verification
- **Resource Management**: Automatic cleanup via ZLayer scoping

## Production Considerations

### Scalability
- **Multiple Connections**: Configurable connection pool size
- **Parallel Processing**: Concurrent message handling
- **Buffer Management**: Configurable queue and stream buffers

### Reliability  
- **Automatic Reconnection**: Failed connections replaced automatically
- **Health Monitoring**: Proactive connection health checks
- **Graceful Shutdown**: Proper resource cleanup on application exit

### Monitoring
- **Connection Metrics**: Real-time connection state tracking
- **Error Classification**: Categorized error reporting
- **Debug Support**: Enhanced logging for troubleshooting

### Deployment
- **Configuration Flexibility**: Environment variables + config files
- **Resource Cleanup**: ZIO.scoped ensures proper cleanup
- **Process Management**: Compatible with containerization and process managers

## Development Workflow

### Building & Running
```bash
sbt compile          # Compile the project
sbt run             # Run the application  
sbt dev             # Continuous compile + restart (sbt-revolver)
```

### Configuration
Set required environment variables:
```bash
export APP_SLACK_APP_TOKEN="xapp-1-..."
export APP_DEBUG_RECONNECTS="true"  # Optional: faster reconnects
```

### Testing
- **Unit Tests**: Individual service testing with Stub layers
- **Integration Tests**: Full pipeline testing with test configurations
- **Manual Testing**: Live Slack workspace integration

This architecture provides a robust, scalable foundation for Slack Socket Mode applications with strong typing, comprehensive error handling, and production-ready reliability features.

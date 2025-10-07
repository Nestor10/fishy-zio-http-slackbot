package com.nestor10.slackbot.infrastructure.observability

import com.nestor10.slackbot.domain.model.conversation._
import com.nestor10.slackbot.domain.model.slack._
import zio._

/** Structured logging context helpers for consistent log annotations.
  *
  * Use these with the @@ operator for clean, inline structured logging. Follows zio-logging
  * idiomatic patterns with ZIOAspect.annotated.
  *
  * Example:
  * {{{
  *   import LogContext._
  *   ZIO.logInfo("Processing message") @@
  *     aiBot @@
  *     threadId(thread.id) @@
  *     operation("generate_response")
  * }}}
  */
object LogContext {

  // ============================================================================
  // Component Tags (subsystem identification)
  // ============================================================================

  def aiBot = ZIOAspect.annotated("component", "ai_bot")

  def storage = ZIOAspect.annotated("component", "storage")

  def socket = ZIOAspect.annotated("component", "socket")

  def socketManager = ZIOAspect.annotated("component", "socket_manager")

  def analytics = ZIOAspect.annotated("component", "analytics")

  def notifications = ZIOAspect.annotated("component", "notifications")

  def orchestrator = ZIOAspect.annotated("component", "orchestrator")

  def registry = ZIOAspect.annotated("component", "processor_registry")

  def eventBus = ZIOAspect.annotated("component", "event_bus")

  def llmService = ZIOAspect.annotated("component", "llm_service")

  def slackApi = ZIOAspect.annotated("component", "slack_api")

  def app = ZIOAspect.annotated("component", "app")

  // ============================================================================
  // Domain Entity IDs (for correlation)
  // ============================================================================

  def threadId(id: ThreadId) = ZIOAspect.annotated("thread_id", id.value.toString)

  def channelId(id: ChannelId) = ZIOAspect.annotated("channel_id", id.value)

  def messageId(id: MessageId) = ZIOAspect.annotated("message_id", id.value.toString)

  def userId(id: UserId) = ZIOAspect.annotated("user_id", id.value)

  def connectionId(id: String) = ZIOAspect.annotated("connection_id", id)

  // ============================================================================
  // Operations (action identification)
  // ============================================================================

  def operation(op: String) = ZIOAspect.annotated("operation", op)

  // ============================================================================
  // LLM-Specific Context
  // ============================================================================

  def llmProvider(provider: String) = ZIOAspect.annotated("llm_provider", provider)

  def llmModel(model: String) = ZIOAspect.annotated("llm_model", model)

  def latency(ms: Long) = ZIOAspect.annotated("latency_ms", ms.toString)

  // ============================================================================
  // Event Types
  // ============================================================================

  def eventType(eventType: String) = ZIOAspect.annotated("event_type", eventType)

  // ============================================================================
  // Error Context
  // ============================================================================

  def errorType(error: String) = ZIOAspect.annotated("error_type", error)

  def reason(reason: String) = ZIOAspect.annotated("reason", reason)
}

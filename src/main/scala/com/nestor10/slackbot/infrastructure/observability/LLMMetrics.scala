package com.nestor10.slackbot.infrastructure.observability

import zio.*
import zio.metrics.*

/** Metrics instrumentation for LLM operations.
  *
  * Provides OpenTelemetry metrics for monitoring LLM request performance, interruptions, and
  * speculative execution patterns.
  *
  * Metrics:
  *   - llm.requests.total (Counter) - Total LLM API calls by provider and model
  *   - llm.interruptions.total (Counter) - Cancelled calls by reason
  *   - llm.latency.seconds (Histogram) - Response time distribution
  *   - thread.workers.active (Gauge) - Current number of active thread workers
  *
  * Zionomicon References:
  *   - Chapter 37: ZIO Metrics (Counter, Histogram, Gauge)
  *   - Chapter 17: Dependency Injection (layer pattern)
  */
trait LLMMetrics:

  /** Increment LLM request counter.
    *
    * @param provider
    *   LLM provider (ollama, openai, anthropic, azure)
    * @param model
    *   Model identifier (e.g., "qwen2.5:0.5b", "gpt-4")
    */
  def recordRequest(provider: String, model: String): UIO[Unit]

  /** Increment LLM interruption counter.
    *
    * @param reason
    *   Interruption reason (user_edit, newer_message, timeout, etc.)
    */
  def recordInterruption(reason: String): UIO[Unit]

  /** Record LLM response latency.
    *
    * @param provider
    *   LLM provider
    * @param model
    *   Model identifier
    * @param latencySeconds
    *   Response time in seconds
    */
  def recordLatency(provider: String, model: String, latencySeconds: Double): UIO[Unit]

  /** Update active thread workers gauge.
    *
    * @param count
    *   Current number of active workers (speculative execution count)
    */
  def setActiveWorkers(count: Int): UIO[Unit]

object LLMMetrics:

  /** Errors that can occur during metrics instrumentation */
  enum Error:
    case MetricsUnavailable(message: String)

  /** Live implementation using ZIO Metrics */
  case class Live() extends LLMMetrics:

    private val requestsCounter = Metric.counterInt("llm_requests_total")

    private val interruptionsCounter = Metric.counterInt("llm_interruptions_total")

    private val latencyHistogram = Metric.histogram(
      "llm_latency_seconds",
      MetricKeyType.Histogram.Boundaries.linear(1.0, 1.0, 60)
    )

    private val activeWorkersGauge = Metric.gauge("thread_workers_active")

    override def recordRequest(provider: String, model: String): UIO[Unit] =
      requestsCounter
        .tagged(
          MetricLabel("provider", provider),
          MetricLabel("model", model)
        )
        .increment
        .unit

    override def recordInterruption(reason: String): UIO[Unit] =
      interruptionsCounter
        .tagged(MetricLabel("reason", reason))
        .increment
        .unit

    override def recordLatency(
        provider: String,
        model: String,
        latencySeconds: Double
    ): UIO[Unit] =
      latencyHistogram
        .tagged(
          MetricLabel("provider", provider),
          MetricLabel("model", model)
        )
        .update(latencySeconds)
        .unit

    override def setActiveWorkers(count: Int): UIO[Unit] =
      activeWorkersGauge.set(count.toDouble).unit

  object Live:
    val layer: ULayer[LLMMetrics] = ZLayer.succeed(Live())

  /** Helper methods for accessing LLMMetrics from ZIO environment */
  def recordRequest(provider: String, model: String): URIO[LLMMetrics, Unit] =
    ZIO.serviceWithZIO[LLMMetrics](_.recordRequest(provider, model))

  def recordInterruption(reason: String): URIO[LLMMetrics, Unit] =
    ZIO.serviceWithZIO[LLMMetrics](_.recordInterruption(reason))

  def recordLatency(
      provider: String,
      model: String,
      latencySeconds: Double
  ): URIO[LLMMetrics, Unit] =
    ZIO.serviceWithZIO[LLMMetrics](_.recordLatency(provider, model, latencySeconds))

  def setActiveWorkers(count: Int): URIO[LLMMetrics, Unit] =
    ZIO.serviceWithZIO[LLMMetrics](_.setActiveWorkers(count))

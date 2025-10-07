package com.nestor10.slackbot.conf

import zio._
import zio.config.magnolia.deriveConfig

final case class LlmConfig(
    baseUrl: String = "http://127.0.0.1:11434", // Ollama default, OpenAI: https://api.openai.com
    apiKey: Option[String] = None, // Required for OpenAI, Anthropic, etc; not needed for Ollama
    model: String = "qwen2.5:0.5b",
    temperature: Option[Double] = Some(0.7),
    maxTokens: Option[Int] = None,
    systemPrompt: String = "You are a helpful assistant in a Slack workspace."
)

final case class OtelConfig(
    serviceName: String,
    instrumentationScopeName: String,
    otlpEndpoint: String
)

final case class AppConfig(
    pingIntervalSeconds: Int,
    slackAppToken: String,
    slackBotToken: String,
    debugReconnects: Boolean,
    socketCount: Int,
    llm: LlmConfig = LlmConfig(),
    otel: OtelConfig
)

object AppConfig {
  given Config[LlmConfig] = deriveConfig[LlmConfig]
  given Config[OtelConfig] = deriveConfig[OtelConfig]
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("app")
  val layer: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(ZIO.config(config))
}

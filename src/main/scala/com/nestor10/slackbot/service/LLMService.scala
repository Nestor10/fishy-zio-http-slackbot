package com.nestor10.slackbot.service

import zio.*
import zio.http.*
import zio.json.*
import com.nestor10.slackbot.domain.model.llm.{ChatMessage, ChatRequest, ChatResponse}
import com.nestor10.slackbot.conf.AppConfig

/** Service for interacting with LLM (Large Language Model) APIs.
  *
  * Provides a ZIO-native interface to OpenAI-compatible chat completion endpoints. Supports both
  * Ollama (local) and OpenAI (cloud) providers.
  *
  * Zionomicon References:
  *   - Chapter 35: ZIO HTTP (HTTP client usage)
  *   - Chapter 24: Retries (retry logic for transient failures)
  *   - Chapter 3: Error Model (typed errors)
  *   - Chapter 17-18: Dependency Injection (provider layers)
  */
trait LLMService:

  /** Send a chat completion request and get the response content.
    *
    * @param messages
    *   Conversation history
    * @param model
    *   Model identifier (e.g., "qwen2.5:0.5b", "gpt-4")
    * @param temperature
    *   Sampling temperature (0.0 to 2.0), higher = more random
    * @param maxTokens
    *   Maximum tokens to generate
    * @return
    *   The assistant's response content
    */
  def chat(
      messages: List[ChatMessage],
      model: String,
      temperature: Option[Double] = None,
      maxTokens: Option[Int] = None
  ): IO[LLMService.Error, String]

  /** Send a chat completion request and get the full response.
    *
    * @param messages
    *   Conversation history
    * @param model
    *   Model identifier
    * @param temperature
    *   Sampling temperature
    * @param maxTokens
    *   Maximum tokens to generate
    * @return
    *   The complete ChatResponse with metadata
    */
  def chatWithResponse(
      messages: List[ChatMessage],
      model: String,
      temperature: Option[Double] = None,
      maxTokens: Option[Int] = None
  ): IO[LLMService.Error, ChatResponse]

object LLMService:

  /** Errors that can occur during LLM API interactions.
    *
    * Follows Chapter 3 (Error Model) patterns for typed error handling.
    */
  enum Error:
    case ApiError(statusCode: Int, message: String, body: Option[String])
    case JsonParseError(message: String, cause: Option[Throwable])
    case NetworkError(message: String, cause: Throwable)
    case RateLimitError(retryAfter: Option[Int])
    case InvalidRequest(message: String)

  object Error:

    extension (e: Error)

      def message: String = e match
        case ApiError(status, msg, body) =>
          s"API error $status: $msg${body.map(b => s" - $b").getOrElse("")}"
        case JsonParseError(msg, cause) =>
          s"JSON parse error: $msg${cause.map(c => s" - ${c.getMessage}").getOrElse("")}"
        case NetworkError(msg, cause) =>
          s"Network error: $msg - ${cause.getMessage}"
        case RateLimitError(retryAfter) =>
          s"Rate limit exceeded${retryAfter.map(r => s", retry after ${r}s").getOrElse("")}"
        case InvalidRequest(msg) =>
          s"Invalid request: $msg"

  /** Live implementation using zio-http Client.
    *
    * Works with any OpenAI-compatible endpoint (Ollama, OpenAI, etc.)
    */
  case class Live(
      client: Client,
      baseUrl: String,
      apiKey: Option[String] = None
  ) extends LLMService:

    override def chat(
        messages: List[ChatMessage],
        model: String,
        temperature: Option[Double],
        maxTokens: Option[Int]
    ): IO[Error, String] =
      chatWithResponse(messages, model, temperature, maxTokens)
        .map(_.firstContent.getOrElse(""))

    override def chatWithResponse(
        messages: List[ChatMessage],
        model: String,
        temperature: Option[Double],
        maxTokens: Option[Int]
    ): IO[Error, ChatResponse] =
      val request = ChatRequest(
        model = model,
        messages = messages,
        temperature = temperature,
        maxTokens = maxTokens,
        stream = Some(false)
      )

      for {
        // Build HTTP request
        requestBody <- ZIO.succeed(request.toJson)
        _ <- ZIO.logDebug(s"🤖 LLM_REQUEST: model=$model, messages=${messages.size}")

        url = s"$baseUrl/v1/chat/completions"
        headers = Headers(
          Header.ContentType(MediaType.application.json)
        ) ++ apiKey.map(key => Headers(Header.Authorization.Bearer(key))).getOrElse(Headers.empty)

        httpRequest = Request.post(url, Body.fromString(requestBody)).addHeaders(headers)

        // Send request (scoped for resource management)
        response <- ZIO.scoped {
          client
            .request(httpRequest)
            .mapError(e => Error.NetworkError("Failed to send request", e))
        }

        // Handle response
        result <- response.status match
          case Status.Ok =>
            response.body.asString
              .mapError(e => Error.NetworkError("Failed to read response body", e))
              .flatMap { body =>
                ZIO
                  .fromEither(body.fromJson[ChatResponse])
                  .mapError(e => Error.JsonParseError(e, None))
              }
              .tap(resp =>
                ZIO.logDebug(
                  s"🤖 LLM_RESPONSE: tokens=${resp.usage.map(_.totalTokens).getOrElse(0)}"
                )
              )

          case Status.TooManyRequests =>
            val retryAfter = response.header(Header.RetryAfter).flatMap(_.renderedValue.toIntOption)
            ZIO.fail(Error.RateLimitError(retryAfter))

          case Status.BadRequest =>
            response.body.asString
              .mapError(e => Error.NetworkError("Failed to read error body", e))
              .flatMap(body => ZIO.fail(Error.InvalidRequest(body)))

          case other =>
            response.body.asString
              .mapError(e => Error.NetworkError("Failed to read error body", e))
              .flatMap(body => ZIO.fail(Error.ApiError(other.code, other.text, Some(body))))
      } yield result

  end Live

  /** Create an LLMService for Ollama (local).
    *
    * @param baseUrl
    *   Base URL for Ollama API (default: http://localhost:11434)
    */
  def ollama(baseUrl: String = "http://localhost:11434"): ZLayer[Client, Nothing, LLMService] =
    ZLayer.fromFunction((client: Client) => Live(client, baseUrl, None))

  /** Create an LLMService for OpenAI (cloud).
    *
    * @param apiKey
    *   OpenAI API key
    * @param baseUrl
    *   Base URL for OpenAI API (default: https://api.openai.com)
    */
  def openai(
      apiKey: String,
      baseUrl: String = "https://api.openai.com"
  ): ZLayer[Client, Nothing, LLMService] =
    ZLayer.fromFunction((client: Client) => Live(client, baseUrl, Some(apiKey)))

  /** Dynamic layer that reads AppConfig and chooses the right provider.
    *
    * If apiKey is present in config, uses cloud provider (OpenAI, Anthropic, etc) with
    * authentication. Otherwise, uses Ollama (local, no auth).
    *
    * This is the recommended layer for applications - it automatically selects the provider based
    * on configuration, making it easy to switch between local development (Ollama) and production
    * (cloud provider) via environment variables.
    *
    * Zionomicon References:
    *   - Chapter 17-18: Dependency Injection - Layer composition patterns
    *   - Chapter 20: Configuring Applications - Reading from AppConfig
    */
  val configured: ZLayer[AppConfig & Client, Nothing, LLMService] =
    ZLayer.fromFunction { (config: AppConfig, client: Client) =>
      config.llm.apiKey match
        case Some(key) =>
          // Cloud provider with API key (OpenAI, Anthropic, Azure, etc)
          Live(client, config.llm.baseUrl, Some(key))
        case None =>
          // Local Ollama (no authentication required)
          Live(client, config.llm.baseUrl, None)
    }

  // Accessor methods (Chapter 19: Contextual Data Types)

  def chat(
      messages: List[ChatMessage],
      model: String,
      temperature: Option[Double] = None,
      maxTokens: Option[Int] = None
  ): ZIO[LLMService, Error, String] =
    ZIO.serviceWithZIO[LLMService](_.chat(messages, model, temperature, maxTokens))

  def chatWithResponse(
      messages: List[ChatMessage],
      model: String,
      temperature: Option[Double] = None,
      maxTokens: Option[Int] = None
  ): ZIO[LLMService, Error, ChatResponse] =
    ZIO.serviceWithZIO[LLMService](_.chatWithResponse(messages, model, temperature, maxTokens))

package com.nestor10.slackbot.domain.llm

import zio.json.*

/** Request to create a chat completion.
  *
  * Compatible with OpenAI's `/v1/chat/completions` endpoint. Also works with Ollama's
  * OpenAI-compatible API.
  */
case class ChatRequest(
    model: String,
    messages: List[ChatMessage],
    temperature: Option[Double] = None,
    @jsonField("max_tokens") maxTokens: Option[Int] = None,
    @jsonField("top_p") topP: Option[Double] = None,
    stream: Option[Boolean] = None
)

object ChatRequest:
  given JsonCodec[ChatRequest] = DeriveJsonCodec.gen[ChatRequest]

/** Token usage information from the API response.
  */
case class Usage(
    @jsonField("prompt_tokens") promptTokens: Int,
    @jsonField("completion_tokens") completionTokens: Int,
    @jsonField("total_tokens") totalTokens: Int
)

object Usage:
  given JsonCodec[Usage] = DeriveJsonCodec.gen[Usage]

/** A single choice from the chat completion response.
  */
case class ChatChoice(
    index: Int,
    message: ChatMessage,
    @jsonField("finish_reason") finishReason: String
)

object ChatChoice:
  given JsonCodec[ChatChoice] = DeriveJsonCodec.gen[ChatChoice]

/** Response from a chat completion request.
  *
  * Compatible with OpenAI's `/v1/chat/completions` response format.
  */
case class ChatResponse(
    id: String,
    @jsonField("object") objectType: String,
    created: Long,
    model: String,
    choices: List[ChatChoice],
    usage: Option[Usage] = None
):
  /** Extract the content from the first choice (most common use case) */
  def firstContent: Option[String] = choices.headOption.map(_.message.content)

  /** Extract content or provide a default */
  def contentOrElse(default: String): String = firstContent.getOrElse(default)

object ChatResponse:
  given JsonCodec[ChatResponse] = DeriveJsonCodec.gen[ChatResponse]

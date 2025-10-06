package com.nestor10.slackbot.domain.llm

import zio.json.*

/** Role of a message in an LLM conversation.
  *
  * OpenAI-compatible role types for chat completions.
  */
enum ChatRole:
  case System
  case User
  case Assistant

object ChatRole:

  given JsonCodec[ChatRole] = JsonCodec.string.transformOrFail(
    {
      case "system"    => Right(System)
      case "user"      => Right(User)
      case "assistant" => Right(Assistant)
      case other       => Left(s"Unknown role: $other")
    },
    {
      case System    => "system"
      case User      => "user"
      case Assistant => "assistant"
    }
  )

/** A single message in an LLM conversation.
  *
  * Represents a message with a role and content, compatible with OpenAI's chat completion API
  * format.
  */
case class ChatMessage(
    role: ChatRole,
    content: String
)

object ChatMessage:
  given JsonCodec[ChatMessage] = DeriveJsonCodec.gen[ChatMessage]

  // Convenience constructors
  def system(content: String): ChatMessage = ChatMessage(ChatRole.System, content)
  def user(content: String): ChatMessage = ChatMessage(ChatRole.User, content)
  def assistant(content: String): ChatMessage = ChatMessage(ChatRole.Assistant, content)

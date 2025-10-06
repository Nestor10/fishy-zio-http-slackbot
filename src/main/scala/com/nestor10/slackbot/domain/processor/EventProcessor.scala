package com.nestor10.slackbot.domain.processor

import zio.*
import com.nestor10.slackbot.domain.service.MessageEventBus.MessageEvent

/** Represents an individual event processor that can handle MessageEvents.
  *
  * Processors are registered with the ProcessorRegistry and receive events from the
  * MessageEventBus. Each processor can decide which events it wants to handle via the `canProcess`
  * method.
  *
  * Zionomicon References:
  *   - Chapter 3: Error Model (IO[Error, Unit])
  *   - Appendix 3: Functional Design (trait-based abstraction)
  */
trait EventProcessor:
  /** Unique name for this processor */
  def name: String

  /** Process a message event */
  def process(event: MessageEvent): IO[EventProcessor.Error, Unit]

  /** Determine if this processor can handle the given event */
  def canProcess(event: MessageEvent): Boolean

object EventProcessor:

  /** Errors that can occur during event processing */
  enum Error:
    case ProcessingFailed(cause: Throwable, processorName: String)

  object Error:

    extension (e: Error)

      def message: String = e match
        case ProcessingFailed(cause, processorName) =>
          s"Processor '$processorName' failed: ${cause.getMessage}"

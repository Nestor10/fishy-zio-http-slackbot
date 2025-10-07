package com.nestor10.slackbot.application

import com.nestor10.slackbot.domain.processor.EventProcessor
import com.nestor10.slackbot.domain.service.MessageEventBus
import com.nestor10.slackbot.infrastructure.observability.LogContext
import zio.*

/** Registry for managing message processors and distributing events.
  *
  * The ProcessorRegistry maintains a set of registered processors and runs a worker fiber that
  * subscribes to the MessageEventBus. When events arrive, they are distributed to all processors
  * that can handle them, with parallel execution and error isolation.
  *
  * Zionomicon References:
  *   - Chapter 5-6: Fiber Model and Concurrency (worker fiber, parallel processing)
  *   - Chapter 9: Ref (shared state for processor set)
  *   - Chapter 12: Hub (subscription to MessageEventBus)
  *   - Chapter 15: Scope (resource management)
  */
trait ProcessorRegistry:
  /** Register a new processor */
  def register(processor: EventProcessor): UIO[Unit]

  /** Unregister a processor by name */
  def unregister(name: String): UIO[Unit]

  /** Get all currently registered processors */
  def getProcessors: UIO[Set[EventProcessor]]

  /** Start the worker fiber that processes events */
  def startProcessing: IO[Nothing, Fiber.Runtime[Nothing, Unit]]

object ProcessorRegistry:

  object Live:

    case class Live(
        processors: Ref[Set[EventProcessor]],
        eventBus: MessageEventBus
    ) extends ProcessorRegistry:

      override def register(processor: EventProcessor): UIO[Unit] =
        processors.update(_ + processor) *>
          ZIO.logInfo(s"Processor registered: ${processor.name}") @@
          LogContext.registry

      override def unregister(name: String): UIO[Unit] =
        processors.update(_.filterNot(_.name == name)) *>
          ZIO.logInfo(s"Processor unregistered: $name") @@
          LogContext.registry

      override def getProcessors: UIO[Set[EventProcessor]] =
        processors.get

      /** Start the event processing worker fiber.
        *
        * Launches a daemon fiber that processes events from the MessageEventBus. The worker fiber
        * is resilient - if it crashes, it automatically restarts to ensure continuous event
        * processing.
        *
        * @note
        *   Processor failures are isolated and don't affect other processors or the event bus.
        * @return
        *   Runtime fiber handle for the worker
        */
      override def startProcessing: IO[Nothing, Fiber.Runtime[Nothing, Unit]] =
        ZIO.logInfo("Starting worker fiber") @@
          LogContext.registry *>
          processEvents.forkDaemon

      /** Core event processing loop.
        *
        * Subscribes to the MessageEventBus and distributes events to all matching processors in
        * parallel. Each processor runs in isolation so failures don't affect other processors or
        * the event bus.
        */
      private def processEvents: ZIO[Any, Nothing, Unit] =
        ZIO
          .scoped {
            eventBus.subscribeStream.mapZIO { event =>
              for {
                procs <- processors.get
                matchingProcs = procs.filter(_.canProcess(event))
                _ <- ZIO.logDebug(
                  s"Event received: ${event.getClass.getSimpleName} -> ${matchingProcs.size} processors"
                ) @@ LogContext.registry
                _ <- ZIO.foreachParDiscard(matchingProcs) { processor =>
                  processor
                    .process(event)
                    .catchAll { error =>
                      ZIO.logError(
                        s"Processor error: ${processor.name} - ${error.message}"
                      ) @@ LogContext.registry @@
                        LogContext.errorType(error.getClass.getSimpleName)
                    }
                    .forkDaemon
                    .unit
                }
              } yield ()
            }.runDrain
          }
          .catchAllCause { cause =>
            ZIO.logErrorCause("Processor worker died - restarting", cause) @@
              LogContext.registry *>
              processEvents
          }

    /** ZLayer construction.
      *
      * Depends on MessageEventBus for event subscription. Creates a Ref to hold the processor set.
      */
    val layer: ZLayer[MessageEventBus, Nothing, ProcessorRegistry] =
      ZLayer.fromZIO {
        for {
          bus <- ZIO.service[MessageEventBus]
          ref <- Ref.make(Set.empty[EventProcessor])
        } yield Live(ref, bus)
      }

  // Accessor methods (Chapter 19: Contextual Data Types)

  def register(processor: EventProcessor): URIO[ProcessorRegistry, Unit] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.register(processor))

  def unregister(name: String): URIO[ProcessorRegistry, Unit] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.unregister(name))

  def getProcessors: URIO[ProcessorRegistry, Set[EventProcessor]] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.getProcessors)

  def startProcessing: URIO[ProcessorRegistry, Fiber.Runtime[Nothing, Unit]] =
    ZIO.serviceWithZIO[ProcessorRegistry](_.startProcessing)

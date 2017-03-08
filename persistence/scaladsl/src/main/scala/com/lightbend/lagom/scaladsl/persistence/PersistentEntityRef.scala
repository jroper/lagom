/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.scaladsl.persistence

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.actor.ActorRef
import java.io.NotSerializableException

import akka.NotUsed
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.pattern.{ask => akkaAsk}
import akka.persistence.query.{EventEnvelope, Sequence}
import akka.persistence.query.scaladsl.{CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import com.lightbend.lagom.internal.scaladsl.persistence.PersistentEntityActor

/**
 * Commands are sent to a [[PersistentEntity]] using a
 * `PersistentEntityRef`. It is retrieved with [[PersistentEntityRegistry#refFor]].
 */
final class PersistentEntityRef[P <: PersistentEntity] private[lagom] (
  val entityId: String,
  region:       ActorRef,
  system:       ActorSystem,
  materializer: Option[Materializer],
  eventsByIdQuery: Option[EventsByPersistenceIdQuery],
  currentEventsByIdQuery: Option[CurrentEventsByPersistenceIdQuery],
  journalId: Option[String],
  entityConstructor: Option[() => P],
  askTimeout:   FiniteDuration
)
  extends NoSerializationVerificationNeeded {

  @deprecated("This class should not be instantiated directly", "1.4")
  def this(entityId: String, region: ActorRef, system: ActorSystem, askTimeout: FiniteDuration) = {
    this(entityId, region, system, None, None, None, None, askTimeout)
  }

  implicit private val timeout = Timeout(askTimeout)

  /**
   * Send the `command` to the [[PersistentEntity]]. The returned
   * `Future` will be completed with the reply from the `PersistentEntity`.
   * The type of the reply is defined by the command (see [[PersistentEntity.ReplyType]]).
   *
   * The `Future` may also be completed with failure, sent by the `PersistentEntity`
   * or a `akka.pattern.AskTimeoutException` if there is no reply within a timeout.
   * The timeout can defined in configuration or overridden using [[#withAskTimeout]].
   */
  def ask[Cmd <: P#Command with PersistentEntity.ReplyType[_]](command: Cmd): Future[command.ReplyType] = {
    import scala.compat.java8.FutureConverters._
    import system.dispatcher
    (region ? CommandEnvelope(entityId, command)).flatMap {
      case exc: Throwable =>
        // not using akka.actor.Status.Failure because it is using Java serialization
        Future.failed(exc)
      case result => Future.successful(result)
    }.asInstanceOf[Future[command.ReplyType]]
  }

  /**
   * The timeout for [[#ask]]. The timeout is by default defined in configuration
   * but it can be adjusted for a specific `PersistentEntityRef` using this method.
   * Note that this returns a new `PersistentEntityRef` instance with the given timeout
   * (`PersistentEntityRef` is immutable).
   */
  def withAskTimeout(timeout: FiniteDuration): PersistentEntityRef[P#Command] =
    new PersistentEntityRef(entityId, region, system, materializer, eventsByIdQuery, currentEventsByIdQuery, journalId, timeout)

  /**
   * Get the stream of current events for this entity, and continue emitting events as they are persisted.
   *
   * This method is designed for high scalability, for example, it can be used for end users to receive a stream of
   * notifications about a particular entity. It guarantees exactly once delivery, however, if a message is missed
   * by the back end infrastructure, that message may not end up being delivered until the next message for that entity
   * is persisted.
   *
   * This source is implemented by starting with a current events query from the given sequence number, and then once
   * those events have been returned, it switches to using an Akka distributed pubsub stream. The distributed pubsub
   * stream
   *
   * @param fromSequenceNr
   * @param atLeastOnce
   * @return
   */
  def eventStream(fromSequenceNr: Long): Source[EventStreamElement[P#Event], NotUsed] = {
    eventsByIdQuery match {
      case Some(query) =>
        val eid = PersistentEntityActor.extractEntityId(entityId)
        query.eventsByPersistenceId(entityId, fromSequenceNr, Long.MaxValue).map {
          case EventEnvelope(offset, _, sequenceNr, event) =>
            new EventStreamElement(eid, event.asInstanceOf[P#Event], Sequence(offset), sequenceNr)
        }
      case None =>
        unsupportedOperation("streaming events by persistence id")
    }
  }

  def futureEventStream

  /**
   * Get the stream of current events for this entity, from the given sequence number.
   *
   * This method differs from [[#eventStream]] in that it only returns events that are currently persisted at the time
   * the stream is materialized, it does not wait for and return future events.
   *
   * It is not recommended to use this in high throughput call handling, especially if the history of events for the
   * entities are long. This method is intended for back office type functions, such as an admin or support personnel
   * doing temporal queries to understand the historical state of the system. For high throughput scenarios, it is
   * recommended that you create a read side designed for handling temporal queries.
   *
   * @param fromSequenceNr The sequence number to get the events from. Sequence numbers start at 0, so to retrieve the
   *                       entire history of the entity, pass in 0.
   * @return The current event stream. The stream will terminate when all events currently persisted for the entity
   *         have been emitted.
   */
  def currentEventStream(fromSequenceNr: Long): Source[EventStreamElement[P#Event], NotUsed] = {
    currentEventsByIdQuery match {
      case Some(query) =>
        val eid = PersistentEntityActor.extractEntityId(entityId)
        query.currentEventsByPersistenceId(entityId, fromSequenceNr, Long.MaxValue).map {
          case EventEnvelope(offset, _, sequenceNr, event) =>
            new EventStreamElement(eid, event.asInstanceOf[P#Event], Sequence(offset), sequenceNr)
        }
      case None =>
        unsupportedOperation("streaming events by persistence id")
    }
  }

  private def unsupportedOperation(name: String): Nothing = {
    val msg = journalId match {
      case None =>
        s"$name is not supported due to direct instantiation using the deprecated constructor of PersistentEntityRef"
      case Some(id) =>
        s"The $journalId Lagom persistence plugin does not support $name"
    }
    throw new UnsupportedOperationException(msg)
  }

  /**
   * Get a stream of the current events of this entity folded with its state.
   *
   * This method is intended to allow temporal queries, for example rendering the state of an entity over time.
   *
   * It is not recommended to use this in high throughput call handling, especially if the history of events for the
   * entities are long. This method is intended for back office type functions, such as an admin or support personnel
   * doing temporal queries to understand the historical state of the system. For high throughput scenarios, it is
   * recommended that you create a read side designed for handling temporal queries.
   */
  def currentEventsWithState: Source[(EventStreamElement[P#Event], P#State), NotUsed] = {
    entityConstructor match {
      case Some(c) =>
        val entity = c()
        val eid = PersistentEntityActor.extractEntityId(entityId)
        entity.internalSetEntityId(eid)
        currentEventStream(0).scan[(Option[EventStreamElement[P#Event]], P#State)]((None, entity.initialState)) {
          case ((_, state), event) =>
            val nextState = entity.behavior(state).eventHandler.applyOrElse(event.event,
              throw PersistentEntity.UnhandledCommandException(
                s"Unhandled event [${event.getClass.getName}] in filtered state for entity [${entity.getClass.getName}] with id [$eid]"))
            (event, nextState)
        }.collect {
          case (Some(event), state) => (event, state)
        }
      case None =>
        unsupportedOperation("Filtered state")
    }
  }

  //  Reasons for why we don't not support serialization of the PersistentEntityRef:
  //  - it will rarely be sent as a message itself, so providing a serializer will not help
  //  - it will be embedded in other messages and the only way we could support that
  //    transparently is to implement java serialization (readResolve, writeReplace)
  //    like ActorRef, but we don't want to encourage java serialization anyway
  //  - serializing/embedding the entityId String in other messages is simple
  //  - might be issues with the type `Command`?
  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef =
    throw new NotSerializableException(s"${getClass.getName} is not serializable. Send the entityId instead.")

  override def toString(): String = s"PersistentEntityRef($entityId)"
}

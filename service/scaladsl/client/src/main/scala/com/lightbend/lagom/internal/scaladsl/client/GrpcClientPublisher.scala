package com.lightbend.lagom.internal.scaladsl.client

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import io.grpc.{ClientCall, Metadata, Status}
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.concurrent.{Future, Promise}

/**
 * Exposes the returned responses from a ClientCall as a Source.
 */
object GrpcClientSource {

  /**
   * Create a source from the client call.
   *
   * This source materializes to a future of the status and trailers received from the wire. The future will never fail,
   * however it may be redeemed with a failed gRPC status if an error was encountered locally.
   *
   * This methods signature is designed for symmetry with the GrpcClientSink.
   *
   * @param clientCall The client call.
   * @param canceller A canceller that should be invoked when this source is cancelled. The reason this is needed is
   *                  that while when this source is cancelled, it will cancel the client call, that signal won't be
   *                  passed on to the upstream sink, which will mean
   * @return The listener to pass to ClientCall.start, and the source of responses. The source *must not* be materialized
   *         until ClientCall.start has been invoked.
   */
  def apply[Request, Response](clientCall: ClientCall[Request, Response])(canceller: () => Unit): (ClientCall.Listener[Response], Source[Response, Future[(Status, Metadata)]]) = {
    val publisher = new GrpcClientPublisher[Request, Response](clientCall, canceller)
    (publisher, Source.fromPublisher(publisher).mapMaterializedValue(_ => publisher.closeFuture))
  }
}

class GrpcClientPublisher[Request, Response](clientCall: ClientCall[Request, Response], canceller: () => Unit) extends ClientCall.Listener[Response] with Publisher[Response] {

  // State starts as NotUsed. It then will change to one of the following three terminal states:
  // A Subscriber - this means the publisher has been subscribed to and any subsequent signals will be passed to the subscriber
  // An exception - this means that the call failed before a subscriber was passed, and so when a subscriber is passed, the failure will immediately be passed on
  // Done - this means that the call was completed successfully before a subscriber was passed, and so when a subscriber is passed, it will be immediately completed
  private val state = new AtomicReference[Any](NotUsed)
  // The grpc ClientCall javadocs don't make it quite clear about whether onClose will be called when the listener cancels.
  // It seems that it may still call onClose with a cancelled status, which we want to avoid calling if we've already cancelled.
  // But, what if it sends a cancelled status, and we didn't cancel? We can't just ignore that. So we track whether we've
  // cancelled, and only ignore it if we have cancelled.
  private val subscriberCancelled = new AtomicBoolean(false)

  private val closePromise = Promise[(Status, Metadata)]()

  /**
   * A future of the result of close.
   *
   * Will either be redeemed with the closed status and trailers, or will be failed if the stream gets cancelled.
   */
  val closeFuture: Future[(Status, Metadata)] = closePromise.future

  final override def onMessage(message: Response): Unit = {
    state.get() match {
      case subscriber: Subscriber[_ >: Response] =>
        subscriber.onNext(message)
      case NotUsed =>
        throw new IllegalStateException("onMessage called when nothing has subscribed and hence no demand has been requested. This indicates a bug in grpc-java.")
      case other =>
        throw new IllegalStateException("onMessage called after onClose has been called. This indicates a bug in grpc-java. Current state is: " + other)
    }
  }

  override def onClose(status: Status, trailers: Metadata): Unit = {
    closePromise.trySuccess((status, trailers))

    if (status == Status.CANCELLED && subscriberCancelled.get()) {
      // Ignore, it's just bouncing back our cancelled signal
    } else {
      val toCloseWith = if (status.isOk) Done else status.asException()
      if (!state.compareAndSet(NotUsed, toCloseWith)) {
        state.get() match {
          case subscriber: Subscriber[_ >: Response] =>
            toCloseWith match {
              case Done => subscriber.onComplete()
              case exception: Exception => subscriber.onError(exception)
            }
          case other =>
            throw new IllegalStateException("onClose called twice, second invocation had status of " + status)
        }
      }
    }
  }

  final override def subscribe(s: Subscriber[_ >: Response]): Unit = {
    if (state.compareAndSet(NotUsed, s)) {
      s.onSubscribe(new Subscription {
        override def cancel(): Unit = {
          canceller()
          subscriberCancelled.set(true)
          closePromise.trySuccess((Status.CANCELLED, new Metadata()))
          clientCall.cancel("Subscriber cancelled stream", null)
        }
        override def request(n: Long): Unit = {
          if (n > Int.MaxValue) {
            clientCall.request(Int.MaxValue)
          } else if (n <= 0) {
            throw new RuntimeException("Demand must be strictly positive")
          } else {
            clientCall.request(n.asInstanceOf[Int])
          }
        }
      })
    } else {
      s.onSubscribe(new Subscription {
        override def cancel(): Unit = ()
        override def request(n: Long): Unit = ()
      })
      state.get() match {
        case _: Subscriber[_ >: Response] =>
          s.onError(new IllegalStateException("This publisher has already been subscribed to."))
        case Done =>
          s.onComplete()
        case exception: Exception =>
          s.onError(exception)
      }
    }
  }
}

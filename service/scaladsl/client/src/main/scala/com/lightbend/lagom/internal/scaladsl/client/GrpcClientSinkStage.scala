package com.lightbend.lagom.internal.scaladsl.client

import akka.stream.scaladsl.Sink
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import io.grpc.{ClientCall, Metadata, Status}

/**
 * Exposes a GrpcClient as a sink for request messages.
 */
object GrpcClientSink {
  type Canceller = () => Unit

  /**
   * Create a sink from the client call.
   *
   * This will start the client call when the sink is materialized.
   *
   * @param clientCall The client call to consume the messages.
   * @param listenerFactory A factory for creating a listener to forward signals to. It takes a callback for cancelling
   *                        this sink in case that listener is externally cancelled, and returns both the listener and
   *                        the value to materialize to.
   * @param headers The headers to send when the client call is started.
   * @return A sink that materializes to the second return value of the listener factory.
   */
  def apply[Request, Response, Listener <: ClientCall.Listener[Response], T](clientCall: ClientCall[Request, Response],
    listenerFactory: Canceller => (Listener, T), headers: Metadata): Sink[Request, T] = {

    Sink.fromGraph(new GrpcClientSinkStage[Request, Response, Listener, T](clientCall, listenerFactory, headers))
  }
}

class GrpcClientSinkStage[Request, Response, Listener <: ClientCall.Listener[Response], T](clientCall: ClientCall[Request, Response],
  listenerFactory: GrpcClientSink.Canceller => (Listener, T), headers: Metadata) extends GraphStageWithMaterializedValue[SinkShape[Request], T] {

  private val in = Inlet[Request]("grpc-sink")

  override def shape: SinkShape[Request] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) {

      // This flag is used in case grpc-java invokes onReady multiple times, which is not clearly
      // specified whether that can happen or not.
      var pulled = false

      val onReadyCallback = getAsyncCallback { _: Unit =>
        if (!isClosed(in) && !pulled) {
          pull(in)
          pulled = true
        }
      }

      val cancelCallback = getAsyncCallback { _: Unit =>
        completeStage()
      }

      val (listener, materializedValue) = listenerFactory(() => cancelCallback.invoke())

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          clientCall.sendMessage(grab(in))
          if (clientCall.isReady) {
            pull(in)
            // Should already be true, but just in case
            pulled = true
          } else {
            pulled = false
          }
        }

        override def onUpstreamFinish(): Unit = {
          try {
            clientCall.halfClose()
          } catch {
            case iae: IllegalStateException =>
              // Ignore, if already cancelled this could throw an exception,
              // so we should ignore it.
          }
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          clientCall.cancel(null, ex)
          listener.onClose(Status.fromThrowable(ex), new Metadata())
        }
      })

      override def preStart(): Unit = {
        clientCall.start(new ClientCall.Listener[Response] {
          override def onMessage(message: Response): Unit = {
            listener.onMessage(message)
          }

          override def onClose(status: Status, trailers: Metadata): Unit = {
            cancelCallback.invoke()
            listener.onClose(status, trailers)
          }

          override def onHeaders(headers: Metadata): Unit = {
            listener.onHeaders(headers)
          }

          override def onReady(): Unit = {
            onReadyCallback.invoke(())
            listener.onReady()
          }
        }, headers)
      }
    }

    (logic, logic.materializedValue)
  }
}

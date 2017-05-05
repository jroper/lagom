/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.scaladsl.client

import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.google.common.io.ByteStreams
import com.lightbend.lagom.internal.scaladsl.client.GrpcChannelPoolActor.ReturnChannel
import com.lightbend.lagom.scaladsl.api.Descriptor.{Call, NamedCallId}
import com.lightbend.lagom.scaladsl.api.deser.{MaterializedValueType, StreamedMessageSerializer, StrictMessageSerializer}
import com.lightbend.lagom.scaladsl.api.{Descriptor, ServiceCall, ServiceLocator}
import com.lightbend.lagom.scaladsl.api.transport.{MessageProtocol, Method, RequestHeader, ResponseHeader}
import io.grpc.MethodDescriptor.{Marshaller, MethodType}
import io.grpc._
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.EventLoopGroup

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

class GrpcServiceCall[Request, ResponseMessage, ServiceCallResponse](
  descriptor:           Descriptor,
  call:                 Call[Request, ResponseMessage],
  channelPool:          GrpcChannelPool,
  serviceLocator:       ServiceLocator,
  requestHeaderHandler: RequestHeader => RequestHeader,
  responseHandler:      (ResponseHeader, ResponseMessage) => ServiceCallResponse
)(implicit executionContext: ExecutionContext) extends ServiceCall[Request, ServiceCallResponse] {

  import GrpcServiceCall._

  private val methodDescriptor = MethodDescriptor.create[ByteString, ByteString](
    MethodType.UNARY,
    MethodDescriptor.generateFullMethodName(descriptor.name, call.callId.asInstanceOf[NamedCallId].name),
    ByteStringMarshaller, ByteStringMarshaller
  )

  override def invoke(request: Request): Future[ServiceCallResponse] = {

    serviceLocator.doWithService(descriptor.name, call) { uri =>

      channelPool.requestChannel(uri.getAuthority).flatMap { channel =>

        val promise = Promise[ServiceCallResponse]()
        promise.future.onComplete { _ =>
          channel.returnChannel()
        }

        val requestHeader = descriptor.headerFilter.transformClientRequest(requestHeaderHandler(
          RequestHeader(Method.POST, URI.create("/" + methodDescriptor.getFullMethodName),
            MessageProtocol.empty.withContentType("application/grpc"), call.requestSerializer.acceptResponseProtocols, None, Nil)
        ))

        // note - the gRPC library does not let us provide our own method, path, content type or user agent, so for now
        // we'll just ignore all of them, and there's no point in sending accept headers if we can't send a content type.
        val requestHeaders = new Metadata()
        requestHeader.headers.foreach {
          case (key, value) => requestHeaders.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
        }
        val clientCall = channel.newCall(methodDescriptor, CallOptions.DEFAULT)

        call.responseSerializer match {
          case strict: StrictMessageSerializer[ResponseMessage] =>
            val
            makeCall(requestHeaders, request, clientCall, _ => )
          case streamed: StreamedMessageSerializer[ResponseMessage, _] =>
            val source = makeCall(requestHeaders, request, clientCall, GrpcClientSource(clientCall).andThen {
              case (delegate, source) => (new ClientCall.Listener[ByteString] {
                override def onMessage(message: ByteString): Unit = delegate.onMessage(message)
                override def onClose(status: Status, trailers: Metadata): Unit = super.onClose(status, trailers)
                override def onHeaders(headers: Metadata): Unit = super.onHeaders(headers)

                override def onReady(): Unit = super.onReady()
              }, source)
            })
            val sourceWithMV = source.mapMaterializedValue { future =>
              streamed.materializedValueType match {
                case MaterializedValueType.NotUsed => NotUsed
                case MaterializedValueType.FutureDone => future.map {
                  case (status, _) if status.isOk => Done
                  case (status, trailers) => throw status.asException(trailers)
                }
                case MaterializedValueType.FutureTrailers => future.map {
                  case (status, trailers) => ResponseHeader(status.getCode.value(), MessageProtocol.empty,
                    metadataToHeaders(trailers))
                }
              }
            }

        }

        responseFuture: Future[Any]
      }
    }.map(_.getOrElse(throw new IllegalStateException("Service not found")))
  }

  override def handleRequestHeader(handler: (RequestHeader) => RequestHeader): ServiceCall[Request, ServiceCallResponse] =
    new GrpcServiceCall[Request, ResponseMessage, ServiceCallResponse](descriptor, call, channelPool, serviceLocator,
      requestHeaderHandler.andThen(handler), responseHandler)

  override def handleResponseHeader[T](handler: (ResponseHeader, ServiceCallResponse) => T): ServiceCall[Request, T] =
    new GrpcServiceCall[Request, ResponseMessage, T](descriptor, call, channelPool, serviceLocator, requestHeaderHandler,
      (header, message) => handler(header, responseHandler(header, message)))

  private def makeCall[R](headers: Metadata, request: Request, clientCall: ClientCall[ByteString, ByteString],
    listenerFactory: (GrpcClientSink.Canceller => (ClientCall.Listener[ByteString], R))): R = {
    call.requestSerializer match {
      case strict: StrictMessageSerializer[Request] =>
        val requestBytes = strict.serializerForRequest.serialize(request)

        val (clientListener, returnValue) = listenerFactory(() => ())
        clientCall.start(clientListener, headers)

        // Must request a message for unary calls
        clientCall.request(1)

        // gRPC impl sends the message after requesting the response, so we do the same
        clientCall.sendMessage(requestBytes)
        clientCall.halfClose()
        returnValue

      case streamed: StreamedMessageSerializer[Any, Any] =>
        streamed.serializerForRequest
          .serialize(request.asInstanceOf[Source[Any, Any]])
          .runWith(GrpcClientSink(clientCall, listenerFactory, headers))
    }
  }

  private def metadataToHeaders(metadata: Metadata): immutable.Seq[(String, String)] = {
    import scala.collection.JavaConverters._
    metadata.keys.asScala.flatMap { key =>
      metadata.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)).asScala.map(key -> _)
    }.to[immutable.Seq]
  }
}

private class GrpcUnaryClientCallListener extends ClientCall.Listener[ByteString] {
  private val promise = Promise[(Option[Metadata], Option[ByteString], Status, Metadata)]()

  /**
   * A future of the headers, message, status, and trailers.
   *
   * The headers and message may be None if an error occurred.
   */
  def future: Future[(Option[Metadata], Option[ByteString], Status, Metadata)] = promise.future

  @volatile
  private var headers: Option[Metadata] = None
  @volatile
  private var message: Option[ByteString] = None

  override def onMessage(message: ByteString): Unit = {
    this.message = Some(message)
  }

  override def onClose(status: Status, trailers: Metadata): Unit = {
    promise.success((headers, message, status, trailers))
  }

  override def onHeaders(headers: Metadata): Unit = {
    this.headers = Some(headers)
  }
}

private class ClientCallListener[Request, ResponseMessage, ServiceCallResponse](requestHeader: RequestHeader, descriptor: Descriptor,
                                                                                call: Call[Request, ResponseMessage], responseHandler: (ResponseHeader, ResponseMessage) => ServiceCallResponse,
                                                                                promise: Promise[ServiceCallResponse]) extends ClientCall.Listener[ByteString] {
  private var headers = immutable.Seq.empty[(String, String)]
  override def onHeaders(headers: Metadata): Unit = {
    try {
      import scala.collection.JavaConverters._
      this.headers = headers.keys.asScala.flatMap { key =>
        headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)).asScala.map(key -> _)
      }.to[immutable.Seq]
    } catch {
      case NonFatal(e) =>
        promise.tryFailure(e)
        throw e
    }
  }
  override def onMessage(message: ByteString): Unit = {
    try {
      val deserializer = call.responseSerializer match {
        case strict: StrictMessageSerializer[ResponseMessage] =>
          strict.deserializer(MessageProtocol.empty)
      }
      val responseMessage = deserializer.deserialize(message)
      val responseHeader = descriptor.headerFilter.transformClientResponse(
        ResponseHeader(200, MessageProtocol.empty, headers), requestHeader
      )
      promise.success(responseHandler(responseHeader, responseMessage))
    } catch {
      case NonFatal(e) =>
        promise.tryFailure(e)
        throw e
    }
  }
  override def onClose(status: Status, trailers: Metadata): Unit = {
    if (promise.isCompleted) {
      // Ignore
    } else {
      if (status.isOk) {
        promise.failure(new RuntimeException("Server did not return any message for unary call"))
      } else {
        // todo translate to Lagom status code
        promise.failure(status.asException())
      }
    }
  }
}

private object GrpcServiceCall {
  val MarshallerBuffer = 8192

  val ByteStringMarshaller: Marshaller[ByteString] = new Marshaller[ByteString] {
    override def stream(value: ByteString): InputStream = value.iterator.asInputStream
    override def parse(stream: InputStream): ByteString = {
      val builder = ByteString.createBuilder
      ByteStreams.copy(stream, builder.asOutputStream)
      builder.result()
    }
  }

  val GrpcProtocol = MessageProtocol.empty.withContentType("application/grpc")
}

/**
 * This is a pool of channels, but not in the traditional sense.
 *
 * Each GRPC channel represents a single connection (though technically could represent a pool of connections), that
 * connection being to a given authority (host). The channel is an expensive object that should be reused between
 * invocations. The LagomService locator however dynamically returns different hosts, for load balancing and because
 * different hosts might go up or down. It might be possible to plug this into GRPC's own load balancing capabilities,
 * but it doesn't seem straight forward, and at current, this seems to be the easiest way to do it. So this pool is
 * essentially a map of results returned from the Lagom service locator to GRPC channels, allowing channels to be
 * reused. In addition, a given result hasn't been seen from the service locator for a while, the channel associated with
 * it will be automatically shut down.  To safely implement this, channels are tracked when they are sent out and
 * returned.
 */
class GrpcChannelPool(actorSystem: ActorSystem, eventLoopGroup: EventLoopGroup) {
  import akka.pattern.ask
  import GrpcChannelPoolActor._
  import scala.concurrent.duration._

  private val actor = actorSystem.actorOf(Props(new GrpcChannelPoolActor(eventLoopGroup)))
  private implicit val timeout = Timeout(10.seconds)

  def requestChannel(authority: String): Future[PooledGrpcChannel] = {
    (actor ? RequestChannel(authority)).mapTo[PooledGrpcChannel]
  }
}

class PooledGrpcChannel(delegate: Channel, actor: ActorRef) extends Channel {

  private val returned = new AtomicBoolean(false)

  override def newCall[RequestT, ResponseT](methodDescriptor: MethodDescriptor[RequestT, ResponseT], callOptions: CallOptions): ClientCall[RequestT, ResponseT] = {
    if (returned.get()) {
      throw new IllegalStateException("Creating a new channel after the channel has been returned.")
    } else {
      delegate.newCall(methodDescriptor, callOptions)
    }
  }

  override def authority(): String = {
    if (returned.get()) {
      throw new IllegalStateException("Using the new channel after the channel has been returned.")
    } else {
      delegate.authority()
    }
  }

  def returnChannel(): Unit = {
    if (returned.compareAndSet(false, true)) {
      actor ! ReturnChannel(delegate)
    }
  }
}

private class GrpcChannelPoolActor(eventLoopGroup: EventLoopGroup) extends Actor {

  import GrpcChannelPoolActor._
  import scala.concurrent.duration._
  import context.dispatcher

  private class PooledChannel(val channel: ManagedChannel) {
    var outstanding: Int = 0
    var lastUse: Long = System.currentTimeMillis()
  }

  private var channels = Map.empty[String, PooledChannel]
  private var channelsByChannel = Map.empty[Channel, PooledChannel]
  private val scheduledTask = context.system.scheduler.schedule(30.seconds, 30.seconds, self, Tick)

  override def postStop(): Unit = {
    channels.foreach {
      case (_, channel) => channel.channel.shutdown()
    }
    scheduledTask.cancel()
  }

  override def receive: Receive = {
    case RequestChannel(authority) =>
      val channel = channels.get(authority) match {
        case Some(existing) => existing
        case None           => createChannel(authority)
      }
      channel.lastUse = System.currentTimeMillis()
      channel.outstanding += 1
      sender ! new PooledGrpcChannel(channel.channel, self)

    case ReturnChannel(channel) =>
      channelsByChannel.get(channel).foreach { channel =>
        channel.outstanding -= 1
      }

    case Tick =>
      val expireIdle = System.currentTimeMillis() - 300000
      val cleanup = System.currentTimeMillis() - 600000
      channels.foreach {
        case (authority, channel) =>
          if (channel.outstanding == 0 && channel.lastUse < expireIdle) {
            channel.channel.shutdown()
            channels -= authority
            channelsByChannel -= channel.channel
          } else if (channel.lastUse < cleanup) {
            // todo log that a channel with outstanding requests is being cleaned up
            channel.channel.shutdown()
            channels -= authority
            channelsByChannel -= channel.channel
          }
      }
  }

  private def createChannel(authority: String): PooledChannel = {
    val channel = NettyChannelBuilder.forTarget(authority)
      .eventLoopGroup(eventLoopGroup)
      // This needs to be true before we work out how to support APLN
      .usePlaintext(true)
      .build()

    val pooledChannel = new PooledChannel(channel)

    channels += (authority -> pooledChannel)
    channelsByChannel += (channel -> pooledChannel)

    pooledChannel
  }
}

private object GrpcChannelPoolActor {
  case class RequestChannel(authority: String)
  case class ReturnChannel(channel: Channel)
  case object Tick
}

package com.kata.pricing.lambda.aws

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.kata.pricing.domain.OrderPricedEvent
import com.kata.pricing.lambda.config.ProcessorConfig
import com.kata.pricing.lambda.port.KinesisPublisher
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest

import java.nio.charset.StandardCharsets

/** The `KinesisPublisher` port, driven by the AWS SDK. */
object KinesisPublisherLive:

  /** The client as a `Resource` (DoD #5).
    *
    * The AWS async clients own a connection pool and an event-loop group; closing them
    * is not optional. `Resource` makes the close a consequence of the acquire rather
    * than a `finally` somebody has to remember. In the handler this is allocated once
    * per container, not once per invocation — building a client per invocation is the
    * classic Lambda mistake and it shows up as latency and exhausted file descriptors.
    */
  def resource[F[_]: Async](config: ProcessorConfig): Resource[F, KinesisPublisher[F]] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        val builder = KinesisAsyncClient.builder().region(config.region)
        config.endpointOverride.fold(builder)(builder.endpointOverride).build()
      })
      .map(client => new Live[F](client, config.streamName))

  private final class Live[F[_]: Async](
      client: KinesisAsyncClient,
      streamName: String
  ) extends KinesisPublisher[F]:

    def publish(event: OrderPricedEvent): F[Unit] =
      val request = PutRecordRequest
        .builder()
        .streamName(streamName)
        .partitionKey(event.partitionKey)
        .data(SdkBytes.fromString(payload(event), StandardCharsets.UTF_8))
        .build()

      Async[F].fromCompletableFuture(Async[F].delay(client.putRecord(request))).void

    /** Hand-written JSON rather than a codec library.
      *
      * `domain` cannot depend on circe without pulling a codec into the pure core, and
      * `lambda` has no HTTP layer to borrow smithy4s' codecs from. The payload is eight
      * flat fields; a dependency to serialise it would cost more than it saves. If the
      * event grows nested structure, revisit this.
      */
    private def payload(event: OrderPricedEvent): String =
      val coupon = event.couponApplied.fold("null")(code => s"\"${code.value}\"")
      s"""{"eventId":"${event.eventId}",""" +
        s""""orderId":"${event.orderId.value}",""" +
        s""""customerId":"${event.customerId.value}",""" +
        s""""subtotal":${event.subtotal.amount},""" +
        s""""discountAmount":${event.discountAmount.amount},""" +
        s""""total":${event.total.amount},""" +
        s""""couponApplied":$coupon,""" +
        s""""createdAt":"${event.createdAt.toString}"}"""

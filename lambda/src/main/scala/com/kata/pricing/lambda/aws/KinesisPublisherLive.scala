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

  /** Escapes a string for embedding inside a hand-written JSON string literal.
    *
    * Hand-written JSON (see `payload` below) is a deliberate choice — the escaping is
    * the price of that choice. Every interpolated field is untrusted from this file's
    * point of view: `eventId`/`orderId`/`customerId` are unvalidated beyond
    * non-blank (`CustomerId.from`, `OrderId.from`), and `couponApplied` arrives from an
    * external DynamoDB `NEW_IMAGE` via the decoder — none of them are guaranteed free of
    * `"`, `\`, or control characters. Skipping this would let a value like `SUM"MER`
    * produce `{"couponApplied":"SUM"MER"}`, which is invalid JSON for every consumer.
    */
  private[aws] def escapeJson(raw: String): String =
    val builder = new StringBuilder(raw.length)
    raw.foreach {
      case '"'                        => builder.append("\\\"")
      case '\\'                       => builder.append("\\\\")
      case '\n'                       => builder.append("\\n")
      case '\r'                       => builder.append("\\r")
      case '\t'                       => builder.append("\\t")
      case c if c < ' '                => builder.append(f"\\u${c.toInt}%04x")
      case c                          => builder.append(c)
    }
    builder.toString

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

  /** Hand-written JSON rather than a codec library.
    *
    * `domain` cannot depend on circe without pulling a codec into the pure core, and
    * `lambda` has no HTTP layer to borrow smithy4s' codecs from. The payload is eight
    * flat fields; a dependency to serialise it would cost more than it saves. If the
    * event grows nested structure, revisit this.
    *
    * Every interpolated string field goes through `escapeJson` — see that method for
    * why it is not optional. Lifted to the companion (`private[aws]`, not nested in
    * `Live`) so it can be pinned by a test without standing up a client.
    */
  private[aws] def payload(event: OrderPricedEvent): String =
    val coupon = event.couponApplied.fold("null")(code => s"\"${escapeJson(code.value)}\"")
    s"""{"eventId":"${escapeJson(event.eventId)}",""" +
      s""""orderId":"${escapeJson(event.orderId.value)}",""" +
      s""""customerId":"${escapeJson(event.customerId.value)}",""" +
      s""""subtotal":${event.subtotal.amount},""" +
      s""""discountAmount":${event.discountAmount.amount},""" +
      s""""total":${event.total.amount},""" +
      s""""couponApplied":$coupon,""" +
      s""""createdAt":"${escapeJson(event.createdAt.toString)}"}"""

  private final class Live[F[_]: Async](
      client: KinesisAsyncClient,
      streamName: String
  ) extends KinesisPublisher[F]:

    /** One `putRecord` per event, matching the port's one-event-at-a-time shape. The
      * scale answer is `putRecords` (batched), but its response reports per-record
      * partial failure that would then need inspecting and reconciling with
      * `StreamProcessor`'s own failure reporting — not a change to make incidentally
      * here.
      */
    def publish(event: OrderPricedEvent): F[Unit] =
      val request = PutRecordRequest
        .builder()
        .streamName(streamName)
        .partitionKey(event.partitionKey)
        .data(SdkBytes.fromString(payload(event), StandardCharsets.UTF_8))
        .build()

      Async[F].fromCompletableFuture(Async[F].delay(client.putRecord(request))).void

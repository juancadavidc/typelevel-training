package com.kata.pricing.it

import cats.effect.{IO, Ref}
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import software.amazon.awssdk.services.dynamodb.model.{
  DescribeStreamRequest,
  DescribeTableRequest,
  GetRecordsRequest,
  GetShardIteratorRequest,
  ShardIteratorType
}
import software.amazon.awssdk.services.kinesis.model.{
  GetRecordsRequest as KinesisGetRecordsRequest,
  GetShardIteratorRequest as KinesisGetShardIteratorRequest,
  ShardIteratorType as KinesisShardIteratorType,
  ListShardsRequest
}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Reading the two streams the way a consumer actually has to.
  *
  * The shard iterator is the subtlety worth naming, because getting it wrong produces a
  * suite that passes by luck: an iterator is a *cursor*, not a handle. Every `GetRecords`
  * returns a `nextShardIterator`, and re-using the original one repeatedly re-reads from
  * the same position — which happens to work when the record arrives on the first poll and
  * silently misses records that arrive on any later one. Both readers below therefore hold
  * the cursor in a `Ref` and advance it after each call.
  *
  * The iterator is also opened *before* the write it is meant to observe. `TRIM_HORIZON`
  * makes that harmless here, but the ordering matters for the same reason in any
  * consumer: a cursor opened after the fact can start past the record you are waiting for,
  * and the resulting failure looks exactly like eventual consistency that never resolved.
  */
object StreamReader:

  /** A cursor over the Orders table's DynamoDB stream, already bridged to the POJOs the
    * Lambda runtime hands the handler.
    *
    * The bridging happens here, at the edge, so the tests never see an SDK v2 `Record` —
    * they work in exactly the types `StreamProcessor` takes in production.
    */
  final class DynamoCursor(stack: TestStack, iterator: Ref[IO, Option[String]]):

    def poll: IO[List[DynamodbStreamRecord]] =
      iterator.get.flatMap {
        case None       => IO.pure(Nil)
        case Some(cursor) =>
          IO.fromCompletableFuture(
            IO(
              stack.streams.getRecords(
                GetRecordsRequest.builder().shardIterator(cursor).build()
              )
            )
          ).flatMap { response =>
            iterator.set(Option(response.nextShardIterator())) *>
              IO.pure(response.records().asScala.toList.map(StreamRecordBridge.toLambdaRecord))
          }
      }

    /** Collects until `enough` records have been seen, or fails with a message naming
      * what was expected — never a bare timeout. */
    def collect(count: Int, description: String, timeout: FiniteDuration = 60.seconds)
        : IO[List[DynamodbStreamRecord]] =
      Polling.collectUntil(timeout, s"$count record(s) on the Orders stream: $description")(poll)(
        _.size >= count
      )

    /** Waits for the records belonging to one specific order, ignoring every other.
      *
      * This is what makes the tests independent of each other, and it is a stronger
      * guarantee than merely running them one at a time. The cursor starts at
      * TRIM_HORIZON — the beginning of the stream — so it replays every order written by
      * every earlier test in the suite. `collect(1)` would therefore hand back the *first*
      * order ever written rather than the one this test just produced, and the test would
      * assert against someone else's data.
      *
      * Filtering on the `orderId` the service just returned removes the whole class of
      * problem: it does not matter what else is on the stream, how many tests ran before,
      * or in what order they ran.
      */
    def collectFor(
        orderId: String,
        count: Int,
        description: String,
        timeout: FiniteDuration = 60.seconds
    ): IO[List[DynamodbStreamRecord]] =
      Polling
        .collectUntil(timeout, s"$count record(s) for order $orderId: $description")(poll)(
          _.count(belongsTo(orderId)) >= count
        )
        .map(_.filter(belongsTo(orderId)))

    /** Reads the key rather than the decoded event, so that a record whose *contents* fail
      * to decode is still recognised as this order's — otherwise a decoding regression
      * would surface as an unhelpful timeout instead of as the decoding failure it is. */
    private def belongsTo(orderId: String)(record: DynamodbStreamRecord): Boolean =
      Option(record.getDynamodb)
        .flatMap(stream => Option(stream.getNewImage))
        .flatMap(image => Option(image.get("orderId")))
        .flatMap(value => Option(value.getS))
        .contains(orderId)

  /** Opens a cursor at TRIM_HORIZON over the table's single shard.
    *
    * One shard is an assumption that holds because the suite creates the table itself and
    * writes a handful of rows; a production consumer would iterate every shard. Stated
    * rather than hidden, since it is the kind of simplification that becomes wrong the
    * moment the table is large.
    */
  def dynamoCursor(stack: TestStack): IO[DynamoCursor] =
    for
      table  <- IO.fromCompletableFuture(
                  IO(
                    stack.dynamo.describeTable(
                      DescribeTableRequest.builder().tableName(LocalStackResource.ordersTable).build()
                    )
                  )
                )
      arn     = table.table().latestStreamArn()
      described <- IO.fromCompletableFuture(
                     IO(
                       stack.streams.describeStream(
                         DescribeStreamRequest.builder().streamArn(arn).build()
                       )
                     )
                   )
      shard  <- IO.fromOption(described.streamDescription().shards().asScala.headOption)(
                  new AssertionError(s"the Orders stream ($arn) has no shards")
                )
      cursor <- IO.fromCompletableFuture(
                  IO(
                    stack.streams.getShardIterator(
                      GetShardIteratorRequest
                        .builder()
                        .streamArn(arn)
                        .shardId(shard.shardId())
                        .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                        .build()
                    )
                  )
                ).map(_.shardIterator())
      ref    <- Ref[IO].of(Option(cursor))
    yield new DynamoCursor(stack, ref)

  /** A cursor over the Kinesis stream the Lambda publishes to, decoding each record's
    * payload as the UTF-8 JSON `KinesisPublisherLive` writes. */
  final class KinesisCursor(stack: TestStack, iterator: Ref[IO, Option[String]]):

    def poll: IO[List[String]] =
      iterator.get.flatMap {
        case None         => IO.pure(Nil)
        case Some(cursor) =>
          IO.fromCompletableFuture(
            IO(
              stack.kinesis.getRecords(
                KinesisGetRecordsRequest.builder().shardIterator(cursor).build()
              )
            )
          ).flatMap { response =>
            iterator.set(Option(response.nextShardIterator())) *>
              IO.pure(
                response
                  .records()
                  .asScala
                  .toList
                  .map(record => new String(record.data().asByteArray(), StandardCharsets.UTF_8))
              )
          }
      }

    def collect(count: Int, description: String, timeout: FiniteDuration = 60.seconds)
        : IO[List[String]] =
      Polling.collectUntil(timeout, s"$count event(s) on Kinesis: $description")(poll)(
        _.size >= count
      )

    /** The Kinesis counterpart of `DynamoCursor.collectFor`, and for the same reason: this
      * cursor also starts at TRIM_HORIZON and replays every event published by every
      * earlier test.
      *
      * Matching on the raw payload rather than a parsed field keeps this reader free of
      * assumptions about the JSON shape — the payload is the published bytes, and the
      * order id appears in it verbatim.
      */
    /** A shorter cap than the DynamoDB side deliberately: by the time this is called,
      * `StreamProcessor.process` has already returned, so the publish either happened or
      * failed — this is waiting on Kinesis's own read-after-write visibility, not on a
      * pipeline that might still be working. 20 seconds is far beyond that and still fails
      * three times faster when the publish did not happen at all, which is the case a
      * developer is most likely to be debugging.
      */
    def collectFor(
        orderId: String,
        count: Int,
        description: String,
        timeout: FiniteDuration = 20.seconds
    ): IO[List[String]] =
      val marker = s""""orderId":"$orderId""""
      Polling
        .collectUntil(timeout, s"$count event(s) for order $orderId: $description")(poll)(
          _.count(_.contains(marker)) >= count
        )
        .map(_.filter(_.contains(marker)))

  def kinesisCursor(stack: TestStack): IO[KinesisCursor] =
    for
      shards <- IO.fromCompletableFuture(
                  IO(
                    stack.kinesis.listShards(
                      ListShardsRequest.builder().streamName(LocalStackResource.kinesisStream).build()
                    )
                  )
                )
      shard  <- IO.fromOption(shards.shards().asScala.headOption)(
                  new AssertionError(s"the ${LocalStackResource.kinesisStream} stream has no shards")
                )
      cursor <- IO.fromCompletableFuture(
                  IO(
                    stack.kinesis.getShardIterator(
                      KinesisGetShardIteratorRequest
                        .builder()
                        .streamName(LocalStackResource.kinesisStream)
                        .shardId(shard.shardId())
                        .shardIteratorType(KinesisShardIteratorType.TRIM_HORIZON)
                        .build()
                    )
                  )
                ).map(_.shardIterator())
      ref    <- Ref[IO].of(Option(cursor))
    yield new KinesisCursor(stack, ref)

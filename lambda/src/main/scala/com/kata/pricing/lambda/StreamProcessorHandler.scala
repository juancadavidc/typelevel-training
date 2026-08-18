package com.kata.pricing.lambda

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.{DynamodbEvent, StreamsEventResponse}
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse.BatchItemFailure
import com.kata.pricing.lambda.aws.KinesisPublisherLive
import com.kata.pricing.lambda.config.ProcessorConfig

import scala.jdk.CollectionConverters.*

/** The composition root, and the only place in the module that knows about `IO`.
  *
  * The class name is fixed: `cdk/lib/compute-stack.ts` declares
  * `com.kata.pricing.lambda.StreamProcessorHandler::handleRequest`. Renaming it without
  * editing the CDK deploys a function that cannot start.
  *
  * The client and runtime are built once, in the constructor, and reused across warm
  * invocations. Allocating them per invocation would pay the connection-pool setup on
  * every record batch.
  */
class StreamProcessorHandler extends RequestHandler[DynamodbEvent, StreamsEventResponse]:

  private given IORuntime = IORuntime.global

  /** The `Resource`'s finalizer (the second element of `.allocated`) is deliberately
    * discarded, not leaked-by-accident: a Lambda container is torn down with SIGKILL,
    * with no guaranteed shutdown hook in which running it would matter, so there is no
    * moment in this process's life at which invoking it would be meaningful. The
    * acquisition itself still goes through `Resource.fromAutoCloseable`
    * (`KinesisPublisherLive.resource`), so this is not hand-rolled client management —
    * it is `Resource` used for what it is good at (guaranteed, ordered acquire) in an
    * environment where its release guarantee has nothing to attach to.
    */
  private val (processor, _) =
    (for
      config    <- ProcessorConfig.load[IO].toResource
      publisher <- KinesisPublisherLive.resource[IO](config)
    yield StreamProcessor[IO](publisher, config.concurrency)).allocated.unsafeRunSync()

  /** Returns a `StreamsEventResponse` because the event source sets
    * `reportBatchItemFailures: true`. Under that flag Lambda reads this value to decide
    * what to retry, and a `void` handler would be read as "the whole batch succeeded" —
    * silently discarding records that were never published.
    */
  def handleRequest(event: DynamodbEvent, context: Context): StreamsEventResponse =
    val records = Option(event.getRecords).map(_.asScala.toList).getOrElse(Nil)
    val result  = processor.process(records).unsafeRunSync()

    val failures = result.failedSequenceNumbers.map { sequenceNumber =>
      BatchItemFailure.builder().withItemIdentifier(sequenceNumber).build()
    }

    StreamsEventResponse.builder().withBatchItemFailures(failures.asJava).build()

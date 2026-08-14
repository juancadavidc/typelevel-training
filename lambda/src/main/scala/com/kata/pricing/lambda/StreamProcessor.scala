package com.kata.pricing.lambda

import cats.effect.Async
import cats.syntax.all.*
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.kata.pricing.lambda.port.KinesisPublisher
import fs2.Stream

/** What the handler must tell Lambda: which records were not successfully processed.
  *
  * Empty means "the whole batch is done". Non-empty tells Lambda where to resume, which
  * only works because the event source sets `reportBatchItemFailures: true`.
  */
final case class ProcessResult(failedSequenceNumbers: List[String])

/** The batch pipeline: records in, Kinesis events out.
  *
  * Polymorphic in `F[_]`, so the suites drive it without `IO.unsafeRun` and the handler
  * is the only place that picks a runtime (DoD #2). `parEvalMap` rather than a loop with
  * side effects is the brief's explicit requirement, and the bound is the point: an
  * unbounded batch opens one Kinesis call per record and gets throttled.
  *
  * `parEvalMap` also preserves output order, which `parEvalMapUnordered` would not.
  * Order matters here because the failure report must name the *first* unprocessed
  * record — Lambda resumes from it, and resuming from the wrong one skips records.
  */
final class StreamProcessor[F[_]: Async](
    publisher: KinesisPublisher[F],
    concurrency: Int
):

  def process(records: List[DynamodbStreamRecord]): F[ProcessResult] =
    Stream
      .emits(records)
      .parEvalMap(concurrency)(handle)
      .compile
      .toList
      .map(outcomes => ProcessResult(failuresFrom(records, outcomes)))

  /** One record's outcome: `true` when it is safely dealt with — published, or correctly
    * skipped. A decode error and a publish error are both `false`; neither may be
    * reported as success. */
  private def handle(record: DynamodbStreamRecord): F[Boolean] =
    StreamDecoder.decode(record) match
      case Left(_)          => Async[F].pure(false)
      case Right(None)      => Async[F].pure(true)
      case Right(Some(evt)) => publisher.publish(evt).as(true).handleError(_ => false)

  /** Fail fast in reporting terms: from the first bad record onward, everything is
    * reported unprocessed.
    *
    * Records after the failure may in fact have been published — `parEvalMap` has
    * several in flight at once. Reporting them anyway is the safe direction: a
    * republished event is byte-identical and the consumer discards it, whereas an
    * unreported failure is an order nobody is ever told about.
    */
  private def failuresFrom(
      records: List[DynamodbStreamRecord],
      outcomes: List[Boolean]
  ): List[String] =
    outcomes.indexOf(false) match
      case -1    => Nil
      case first => records.drop(first).map(_.getDynamodb.getSequenceNumber)

object StreamProcessor:
  def apply[F[_]: Async](publisher: KinesisPublisher[F], concurrency: Int): StreamProcessor[F] =
    new StreamProcessor[F](publisher, concurrency)

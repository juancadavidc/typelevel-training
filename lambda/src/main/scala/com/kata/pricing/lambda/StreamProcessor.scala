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
      .map(outcomes => ProcessResult(failuresFrom(outcomes)))

  /** One record's outcome, paired with the identifier `failuresFrom` reports it under.
    *
    * The sequence number is read here, once, directly alongside the record it came from
    * — never re-read later from the raw `DynamodbStreamRecord` list. That is deliberate:
    * `failuresFrom` used to re-dereference `record.getDynamodb.getSequenceNumber` after
    * the fact, which crashed with an NPE on exactly the record `StreamDecoder` had just
    * reported as a recoverable `Left` (a null `dynamodb` payload — see `StreamDecoder`
    * line 34). That crash escaped `handle`'s `Boolean` entirely, so `process` threw
    * instead of returning a `ProcessResult`, and `handleRequest` threw instead of
    * returning a `StreamsEventResponse` — under `reportBatchItemFailures: true` that
    * fails the *whole* batch, including records that published successfully. Reading
    * the sequence number once, right here, removes the class of bug: there is no later
    * point in the pipeline that re-reads the raw record and can NPE on it.
    *
    * Both `getDynamodb` and `getSequenceNumber` are guarded: either can be null on a
    * malformed record (the same "AWS handed us a POJO with holes in it" reality
    * `StreamDecoder` already assumes). When no sequence number can be named, the record
    * is still reported — under a placeholder id rather than silently dropped from the
    * list. Naming a record Lambda cannot actually resume from is a genuine dilemma with
    * no clean answer; the safe direction is to over-report; a duplicate "resume from
    * seq-X" is deduped downstream by `eventId`, but a failure that never appears in the
    * list is an order lost forever. This id will make Lambda's resume-from-here-marker
    * meaningless for that one entry, but it still counts the batch as not-fully-done,
    * which is what actually prevents data loss here (see `ProcessResult` above).
    */
  private def handle(record: DynamodbStreamRecord): F[(String, Boolean)] =
    val sequenceNumber =
      Option(record.getDynamodb)
        .flatMap(stream => Option(stream.getSequenceNumber))
        .getOrElse("<unknown-sequence-number>")

    val outcome = StreamDecoder.decode(record) match
      case Left(_)          => Async[F].pure(false)
      case Right(None)      => Async[F].pure(true)
      case Right(Some(evt)) => publisher.publish(evt).as(true).handleError(_ => false)

    outcome.tupleLeft(sequenceNumber)

  /** Fail fast in reporting terms: from the first bad record onward, everything is
    * reported unprocessed.
    *
    * Records after the failure may in fact have been published — `parEvalMap` has
    * several in flight at once. Reporting them anyway is the safe direction: a
    * republished event is byte-identical and the consumer discards it, whereas an
    * unreported failure is an order nobody is ever told about.
    */
  private def failuresFrom(outcomes: List[(String, Boolean)]): List[String] =
    outcomes.indexWhere(!_._2) match
      case -1    => Nil
      case first => outcomes.drop(first).map(_._1)

object StreamProcessor:
  def apply[F[_]: Async](publisher: KinesisPublisher[F], concurrency: Int): StreamProcessor[F] =
    new StreamProcessor[F](publisher, concurrency)

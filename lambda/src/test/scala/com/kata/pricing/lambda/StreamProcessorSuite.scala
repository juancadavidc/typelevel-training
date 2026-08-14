package com.kata.pricing.lambda

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.kata.pricing.domain.OrderPricedEvent
import com.kata.pricing.lambda.port.KinesisPublisher
import weaver.SimpleIOSuite

object StreamProcessorSuite extends SimpleIOSuite:

  private def processorWith(
      publisher: KinesisPublisher[IO],
      concurrency: Int = 4
  ): StreamProcessor[IO] = StreamProcessor[IO](publisher, concurrency)

  test("every record in the batch is published") {
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.insertRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      result <- processorWith(publisher).process(records)
      seen   <- published
    // Order-independent on purpose: `publish` runs inside `parEvalMap`, so the effects
    // race and can land in the recording `Ref` in any order — only `parEvalMap`'s
    // OUTPUT stream is guaranteed ordered, not the side effects that produce it. What
    // this test actually promises is WHICH records got published, not in what sequence
    // their publishes completed. Compare as sets so a scheduling change can't flake it.
    yield expect.eql(seen.size, 3) and
      expect.eql(seen.map(_.orderId.value).toSet, Set("order-1", "order-2", "order-3")) and
      expect.eql(result.failedSequenceNumbers, Nil)
  }

  /** The test that backs DoD #4. At-least-once delivery means this batch WILL be
    * redelivered sooner or later; the guarantee is that redelivery is indistinguishable
    * from the original, so the consumer can drop it. */
  test("reprocessing the same record produces an identical eventId") {
    val record = Fixtures.insertRecord("order-1", sequenceNumber = "seq-1")
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      processor = processorWith(publisher)
      _    <- processor.process(List(record))
      _    <- processor.process(List(record))
      seen <- published
    yield expect.eql(seen.size, 2) and
      expect.eql(seen(0).eventId, seen(1).eventId) and
      // `==`, not `expect.eql`: comparing two `OrderPricedEvent`s needs a cats `Eq`,
      // and this project defines none anywhere. Byte-identity of the whole event is
      // the actual guarantee here, so the structural comparison is the assertion.
      expect(seen(0) == seen(1))
  }

  test("REMOVE records are ignored and publish nothing") {
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      records = List(Fixtures.removeRecord("order-1"), Fixtures.removeRecord("order-2"))
      result <- processorWith(publisher).process(records)
      seen   <- published
    yield expect(seen.isEmpty) and expect.eql(result.failedSequenceNumbers, Nil)
  }

  test("a mixed batch publishes only the priced records") {
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.removeRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      _    <- processorWith(publisher).process(records)
      seen <- published
    // Same reasoning as "every record in the batch is published": `publish` effects
    // race under `parEvalMap`, so the order they land in the recording `Ref` is not
    // guaranteed. The intent here is "REMOVE is skipped, the two priced orders are
    // not" — a set comparison says exactly that without depending on timing.
    yield expect.eql(seen.map(_.orderId.value).toSet, Set("order-1", "order-3"))
  }

  /** Fail fast, and report honestly. The CDK sets `reportBatchItemFailures: true`, so an
    * empty failure list means "all succeeded" — returning that after an abort would tell
    * Lambda to advance past records that were never published.
    *
    * `concurrency = 1` degenerates `parEvalMap` to `evalMap` (fs2 3.13.0,
    * `Stream.scala:2306`), which is strictly sequential and never exercises the
    * concurrent path — the one where `failuresFrom`'s positional correlation across
    * out-of-order completions actually matters. `concurrency = 2` keeps the real
    * `parEvalMap` machinery in play while the failure is still deterministic, because
    * `failingPublisherFor` targets a record by identity (`orderId`), not by which
    * publish happens to complete first — the latter would race under concurrency > 1.
    */
  test("a publish failure reports the failing sequence number and those after it") {
    val boom = new RuntimeException("kinesis is down")
    for
      (publisher, _) <- Fixtures.failingPublisherFor[IO](failOrderId = "order-2", error = boom)
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.insertRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      result <- processorWith(publisher, concurrency = 2).process(records)
    yield expect.eql(result.failedSequenceNumbers, List("seq-2", "seq-3"))
  }

  test("a failure on the first record reports every sequence number") {
    val boom = new RuntimeException("kinesis is down")
    for
      (publisher, _) <- Fixtures.failingPublisherFor[IO](failOrderId = "order-1", error = boom)
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.insertRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      result <- processorWith(publisher, concurrency = 1).process(records)
    yield expect.eql(result.failedSequenceNumbers, List("seq-1", "seq-2", "seq-3"))
  }

  test("a failure on the last record reports only that one") {
    val boom = new RuntimeException("kinesis is down")
    for
      (publisher, _) <- Fixtures.failingPublisherFor[IO](failOrderId = "order-3", error = boom)
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.insertRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      result <- processorWith(publisher, concurrency = 1).process(records)
    yield expect.eql(result.failedSequenceNumbers, List("seq-3"))
  }

  test("an empty batch reports no failures") {
    for
      (publisher, _) <- Fixtures.recordingPublisher[IO]
      result <- processorWith(publisher).process(Nil)
    yield expect.eql(result.failedSequenceNumbers, Nil)
  }

  test("a successful batch reports no failures") {
    for
      (publisher, _) <- Fixtures.recordingPublisher[IO]
      result <- processorWith(publisher).process(
        List(Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"))
      )
    yield expect.eql(result.failedSequenceNumbers, Nil)
  }

  /** A malformed record is reported, not dropped. An order was priced; if we cannot turn
    * it into an event we must not tell Lambda we did. */
  test("an undecodable record is reported as a failure") {
    val broken = Fixtures.insertRecord("order-1", sequenceNumber = "seq-1")
    broken.getDynamodb.getNewImage.remove("orderId")
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      result <- processorWith(publisher).process(List(broken))
      seen   <- published
    yield expect(seen.isEmpty) and expect.eql(result.failedSequenceNumbers, List("seq-1"))
  }

  /** Pins the crash the final review found: a record whose `dynamodb` payload is null
    * decodes to `Left` (a recoverable failure, per `StreamDecoder`), but `failuresFrom`
    * used to re-dereference `record.getDynamodb.getSequenceNumber` to build the report —
    * an NPE on exactly this record. That NPE escaped `handle`'s `F[Boolean]`, so
    * `process` failed the whole `F`, taking the healthy record in the same batch down
    * with it. This asserts the mixed batch neither throws nor loses the good record. */
  test("a record with a null dynamodb payload does not crash the batch and is reported") {
    val malformed = Fixtures.malformedRecord(eventName = "INSERT")
    val good      = Fixtures.insertRecord("order-1", sequenceNumber = "seq-1")
    for
      (publisher, published) <- Fixtures.recordingPublisher[IO]
      result <- processorWith(publisher, concurrency = 1).process(List(malformed, good))
      seen   <- published
    yield expect.eql(seen.map(_.orderId.value).toList, List("order-1")) and
      expect.eql(result.failedSequenceNumbers, List("<unknown-sequence-number>", "seq-1"))
  }

  /** The other half of the same guard: `dynamodb` is present but `sequenceNumber` is
    * null on it. Same crash shape, different null. */
  test("a record with a null sequence number does not crash the batch and is reported") {
    val noSeq = Fixtures.recordWithoutSequenceNumber("order-9")
    noSeq.getDynamodb.getNewImage.remove("orderId") // force a decode failure => reported
    for
      (publisher, _) <- Fixtures.recordingPublisher[IO]
      result <- processorWith(publisher, concurrency = 1).process(List(noSeq))
    yield expect.eql(result.failedSequenceNumbers, List("<unknown-sequence-number>"))
  }

  /** Bounded concurrency is a brief requirement, not a detail: an unbounded batch opens
    * as many concurrent Kinesis calls as there are records and earns throttling. */
  test("no more than `concurrency` publishes are ever in flight") {
    val limit = 3
    for
      inFlight <- Ref[IO].of(0)
      peak     <- Ref[IO].of(0)
      publisher = new KinesisPublisher[IO]:
        def publish(event: OrderPricedEvent): IO[Unit] =
          inFlight.updateAndGet(_ + 1).flatMap(now => peak.update(_.max(now))) *>
            IO.sleep(scala.concurrent.duration.DurationInt(5).millis) *>
            inFlight.update(_ - 1)
      records = (1 to 20).toList.map(i =>
        Fixtures.insertRecord(s"order-$i", sequenceNumber = s"seq-$i")
      )
      _       <- processorWith(publisher, concurrency = limit).process(records)
      maximum <- peak.get
    yield expect(maximum <= limit) and expect(maximum > 1)
  }

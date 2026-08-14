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
    yield expect.eql(seen.size, 3) and
      expect.eql(seen.map(_.orderId.value).toList, List("order-1", "order-2", "order-3")) and
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
    yield expect.eql(seen.map(_.orderId.value).toList, List("order-1", "order-3"))
  }

  /** Fail fast, and report honestly. The CDK sets `reportBatchItemFailures: true`, so an
    * empty failure list means "all succeeded" — returning that after an abort would tell
    * Lambda to advance past records that were never published. */
  test("a publish failure reports the failing sequence number and those after it") {
    val boom = new RuntimeException("kinesis is down")
    for
      (publisher, _) <- Fixtures.failingPublisher[IO](failOn = 2, error = boom)
      records = List(
        Fixtures.insertRecord("order-1", sequenceNumber = "seq-1"),
        Fixtures.insertRecord("order-2", sequenceNumber = "seq-2"),
        Fixtures.insertRecord("order-3", sequenceNumber = "seq-3")
      )
      result <- processorWith(publisher, concurrency = 1).process(records)
    yield expect(result.failedSequenceNumbers.nonEmpty) and
      expect.eql(result.failedSequenceNumbers.head, "seq-2")
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

package com.kata.pricing.it

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.kata.pricing as api
import com.kata.pricing.domain.{Catalog, CouponCode, CustomerId, OrderId, OrderPricedEvent, PricingFlow}
import com.kata.pricing.domain.port.CouponRepo
import com.kata.pricing.lambda.{ProcessResult, StreamDecoder, StreamProcessor}
import com.kata.pricing.lambda.aws.KinesisPublisherLive
import com.kata.pricing.lambda.config.ProcessorConfig
import com.kata.pricing.service.adapter.dynamo.{
  CouponRepoDynamo,
  CustomerRepoDynamo,
  OrderRepoDynamo
}
import com.kata.pricing.service.adapter.id.UuidIdGen
import com.kata.pricing.service.adapter.loyalty.LoyaltyClientHttp4s
import com.kata.pricing.service.adapter.rest.PricingServiceImpl
import com.kata.pricing.service.adapter.tracing.Tracing
import com.kata.pricing.service.adapter.tracing.Tracing.App
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import weaver.*

import scala.concurrent.duration.*

/** The seam nobody else tests.
  *
  * `service` writes an order with `software.amazon.awssdk...dynamodb.model.AttributeValue`;
  * `lambda` reads it back with `com.amazonaws...events.models.dynamodb.AttributeValue`.
  * '''These are unrelated classes''' — nothing in the type system connects them. The
  * contract between the two modules is a set of strings (`orderId`, `subtotal`,
  * `discountAmount`, `createdAt`) plus the convention that `Money` travels as `N` and
  * `Instant` as `S`.
  *
  * Before this suite existed, renaming `discountAmount` in `OrderRepoDynamo` left every
  * test in the repo green while the deployed system was broken: `service` suites never see
  * the decoder, `lambda` suites never see the repo, and `StreamDecoderSuite` hand-builds
  * the POJOs it expects to receive — so it asserts the decoder against itself. The seam is
  * exactly where the two modules do not meet, which is why this module depends on both.
  *
  * ==What this suite does not cover==
  *
  * `StreamProcessor` runs here in-process, off sbt's classpath — not from the assembled
  * jar. The phase 8 `META-INF/services` failure, where the fat jar could not construct its
  * Kinesis client while 71 unit tests stayed green, would still slip through. Only
  * `make deploy` plus a real invocation covers packaging, and the CDK stacks are likewise
  * validated there rather than here. That is why `make up && make deploy` still earn their
  * place in the DoD chain ahead of `make test-integration`.
  */
object IntegrationSuite extends IOSuite:

  override type Res = TestStack
  override def sharedResource: Resource[IO, TestStack] = LocalStackResource.resource

  /** One test at a time. Note that this, not sbt's `Test / parallelExecution`, is the
    * setting that achieves it: weaver runs the tests *within* a suite concurrently, while
    * the sbt setting governs parallelism *between* suites.
    *
    * This is a legibility choice rather than a correctness one, and the distinction is
    * worth being precise about. Correctness comes from `collectFor`, which selects records
    * by the `orderId` under test — the suite was verified to pass with `maxParallelism =
    * 4`, twice, before this was set back to 1. What sequential execution buys is a
    * readable failure: concurrent tests interleave their polling against one shared
    * LocalStack, so a genuine failure arrives amid three other tests' traffic. Serial
    * execution costs about a second here and makes the first failure the one you read.
    */
  override def maxParallelism: Int = 1

  /** Isolation comes from the data, not from containers — and specifically from selecting
    * records by the `orderId` the service just generated.
    *
    * The four tests share one container, one table and one Kinesis stream; a container per
    * test would quadruple a ~15s startup to buy isolation available far more cheaply.
    * What does *not* provide that isolation, and was the first attempt: opening a cursor
    * per test. A cursor starts at TRIM_HORIZON — the beginning of the stream — so every
    * test replays every order written by every test before it, and a plain "wait for one
    * record" hands back the oldest order rather than this test's. That is not a
    * theoretical concern: it is what the first run of this suite did, and the failure read
    * as a mismatched `orderId` rather than as a test-isolation problem.
    *
    * So each test waits for records matching the id `UuidIdGen` produced for its own
    * write (`collectFor`). It then does not matter what else is on the stream, how many
    * tests ran first, or in what order — which also makes the suite safe to re-run against
    * a stream that already holds data, with no truncate-between-tests.
    */

  /** The brief's worked example: 2×SKU-001 @ 19.99 + 1×SKU-045 @ 49.99 = 89.97, less
    * SUMMER10's 10% floored to 8.99, giving 80.98. */
  private val exampleItems = List(
    api.OrderItemInput("SKU-001", 2),
    api.OrderItemInput("SKU-045", 1)
  )

  private val catalog: Catalog = Catalog.of(
    "SKU-001" -> BigDecimal("19.99"),
    "SKU-045" -> BigDecimal("49.99")
  )

  // ---------------------------------------------------------------------------------
  // The producer side: the real service, wired to the real DynamoDB.
  // ---------------------------------------------------------------------------------

  /** The service exactly as `Main` composes it, minus the HTTP transport.
    *
    * The adapters, the flow and the smithy4s-generated interface are the production ones;
    * only the config values differ. Going through `PricingServiceImpl` rather than calling
    * `OrderRepoDynamo` directly is what makes test 1 an end-to-end assertion: validation,
    * pricing, the partner call and the write all take part.
    */
  private def pricingService(stack: TestStack): Resource[IO, api.PricingService[App]] =
    EmberClientBuilder.default[IO].build.map { httpClient =>
      val flow = PricingFlow[App](
        customers = CustomerRepoDynamo[App](stack.dynamo, LocalStackResource.customersTable),
        coupons = CouponRepoDynamo[App](stack.dynamo, LocalStackResource.couponsTable),
        loyalty = LoyaltyClientHttp4s[App](
          httpClient.translate(Tracing.liftK)(Tracing.runNoSpanK),
          Uri.unsafeFromString(stack.loyaltyBaseUri),
          500.millis
        ),
        orders = OrderRepoDynamo[App](stack.dynamo, LocalStackResource.ordersTable),
        ids = UuidIdGen[App],
        catalog = catalog
      )

      PricingServiceImpl[App](flow)
    }

  /** Runs an `App` computation by supplying the no-op span, the same way `Main` does for
    * work that happens outside a request. */
  private def run[A](app: App[A]): IO[A] = Tracing.runNoSpanK(app)

  /** Stops the test the moment the pipeline reports a record it could not process.
    *
    * Without this the next step waits on Kinesis for records that are never coming, and
    * the test eventually fails with a timeout that says nothing about the cause.
    * `StreamProcessor` deliberately converts a failed publish into a reported sequence
    * number rather than an exception — correct for production, where the batch must still
    * return a failure report to Lambda, but it means the *reason* never propagates. This
    * turns "the pipeline told us it failed" into an immediate, named failure instead of a
    * silent 20-second wait.
    */
  private def failFastOnUnprocessed(result: ProcessResult): IO[Unit] =
    IO.raiseWhen(result.failedSequenceNumbers.nonEmpty)(
      new AssertionError(
        s"StreamProcessor reported ${result.failedSequenceNumbers.size} unprocessed " +
          s"record(s): ${result.failedSequenceNumbers.mkString(", ")}. The publish to " +
          "Kinesis failed; StreamProcessor swallows the cause by design, so check the " +
          "publisher's configuration (endpoint, stream name, credentials)."
      )
    )

  // ---------------------------------------------------------------------------------
  // The consumer side: the real Lambda pipeline, publishing to the real Kinesis.
  // ---------------------------------------------------------------------------------

  /** `StreamProcessor` and `KinesisPublisherLive` as the handler builds them — the only
    * difference is the endpoint, which points at the container. */
  private def processor(stack: TestStack): Resource[IO, StreamProcessor[IO]] =
    KinesisPublisherLive
      .resource[IO](
        ProcessorConfig(
          region = stack.region,
          endpointOverride = Some(stack.endpoint),
          streamName = LocalStackResource.kinesisStream,
          concurrency = 4
        )
      )
      .map(publisher => StreamProcessor[IO](publisher, concurrency = 4))

  // ---------------------------------------------------------------------------------
  // 1. The full path: API → DynamoDB → Streams → StreamProcessor → Kinesis.
  // ---------------------------------------------------------------------------------

  test("the brief's example order enters through the API and comes out of Kinesis") { stack =>
    (pricingService(stack), processor(stack)).tupled.use { (service, streamProcessor) =>
      for
        // Cursors are opened *before* the write, so the record cannot be missed.
        dynamoCursor  <- StreamReader.dynamoCursor(stack)
        kinesisCursor <- StreamReader.kinesisCursor(stack)

        response <- run(
                      service.priceOrder(
                        api.CustomerId("cust-123"),
                        exampleItems,
                        Some(api.CouponCode("SUMMER10"))
                      )
                    )

        records  <- dynamoCursor.collectFor(response.orderId.value, 1, "the priced order")
        result   <- streamProcessor.process(records)
        _        <- failFastOnUnprocessed(result)
        payloads <- kinesisCursor.collectFor(
                      response.orderId.value,
                      1,
                      "the published OrderPriced event"
                    )
        payload = payloads.head
      yield expect.all(
        // The brief's numbers, produced by the real pricing flow.
        response.subtotal.value == BigDecimal("89.97"),
        response.discountAmount.value == BigDecimal("8.99"),
        response.total.value == BigDecimal("80.98"),
        // The batch was fully processed: nothing reported back to Lambda as failed.
        result.failedSequenceNumbers.isEmpty,
        // The same order, having survived both AttributeValue worlds and the JSON encoder.
        payload.contains(s""""orderId":"${response.orderId.value}""""),
        payload.contains(""""subtotal":89.97"""),
        payload.contains(""""discountAmount":8.99"""),
        payload.contains(""""total":80.98"""),
        payload.contains(""""couponApplied":"SUMMER10"""")
      )
    }
  }

  // ---------------------------------------------------------------------------------
  // 2. The attribute contract — the test this phase exists for.
  // ---------------------------------------------------------------------------------

  /** The only test in the repo that fails when the two `AttributeValue` worlds drift.
    *
    * It compares the event decoded off the real stream with the event
    * `OrderPricedEvent.from` builds directly from the producer's own `PricedOrder`. Both
    * sides describe the same order, so every field must agree — and they can only agree if
    * `OrderRepoDynamo` writes exactly the attribute names, types and formats
    * `StreamDecoder` reads.
    *
    * Rename `discountAmount` on the writer and this fails; add an attribute the decoder
    * requires and forget to write it, and this fails. Nothing else in the repo does.
    */
  test("the event decoded off the stream matches what the producer built") { stack =>
    pricingService(stack).use { service =>
      for
        cursor <- StreamReader.dynamoCursor(stack)

        response <- run(
                      service.priceOrder(
                        api.CustomerId("cust-123"),
                        exampleItems,
                        Some(api.CouponCode("SUMMER10"))
                      )
                    )

        records <- cursor.collectFor(
                     response.orderId.value,
                     1,
                     "the order whose attributes are under test"
                   )
        decoded <- IO.fromEither(
                     StreamDecoder
                       .decode(records.head)
                       .leftMap(reason =>
                         new AssertionError(
                           s"the writer's attributes did not decode: $reason — " +
                             "OrderRepoDynamo and StreamDecoder have drifted apart"
                         )
                       )
                   )
        event <- IO.fromOption(decoded)(
                   new AssertionError("an INSERT decoded to no event at all")
                 )

        // The producer's own view of the same order, built by the domain rather than read
        // back from DynamoDB.
        expected = OrderPricedEvent(
                     eventId = OrderPricedEvent.eventIdFor(
                       OrderId.unsafe(response.orderId.value),
                       response.createdAt.toInstant
                     ),
                     orderId = OrderId.unsafe(response.orderId.value),
                     customerId = CustomerId.unsafe(response.customerId.value),
                     subtotal = com.kata.pricing.domain.Money(response.subtotal.value),
                     discountAmount = com.kata.pricing.domain.Money(response.discountAmount.value),
                     total = com.kata.pricing.domain.Money(response.total.value),
                     couponApplied = response.couponApplied.map(code => CouponCode.unsafe(code.value)),
                     createdAt = response.createdAt.toInstant
                   )
      yield expect.all(
        event.orderId == expected.orderId,
        event.customerId == expected.customerId,
        event.subtotal == expected.subtotal,
        event.discountAmount == expected.discountAmount,
        event.total == expected.total,
        event.couponApplied == expected.couponApplied,
        event.createdAt == expected.createdAt,
        // The derived id agreeing proves the inputs it is derived from agree too.
        event.eventId == expected.eventId
      )
    }
  }

  // ---------------------------------------------------------------------------------
  // 3. Idempotency: at-least-once delivery survived.
  // ---------------------------------------------------------------------------------

  /** What CDC does *not* give you for free.
    *
    * DynamoDB Streams delivers at least once, so a retried batch replays records that were
    * already published. Dropping the outbox table removed the need for a transaction; it
    * did not remove the need for the consumer to be idempotent. `OrderPricedEvent.eventId`
    * is derived from the order's identity rather than generated, so reprocessing the same
    * record produces a byte-identical event and the downstream consumer can discard it.
    *
    * Two Kinesis records, one logical event — asserted as exactly that: the payloads are
    * identical, so a consumer deduplicating on `eventId` sees one event.
    */
  test("processing the same record twice yields the same eventId") { stack =>
    (pricingService(stack), processor(stack)).tupled.use { (service, streamProcessor) =>
      for
        dynamoCursor  <- StreamReader.dynamoCursor(stack)
        kinesisCursor <- StreamReader.kinesisCursor(stack)

        response <- run(
                      service.priceOrder(
                        api.CustomerId("cust-456"),
                        List(api.OrderItemInput("SKU-001", 1)),
                        None
                      )
                    )

        records <- dynamoCursor.collectFor(
                     response.orderId.value,
                     1,
                     "the order to be processed twice"
                   )

        // The same records through the same pipeline, twice — exactly what Lambda does
        // when a batch is retried.
        first  <- streamProcessor.process(records)
        _      <- failFastOnUnprocessed(first)
        second <- streamProcessor.process(records)
        _      <- failFastOnUnprocessed(second)

        payloads <- kinesisCursor.collectFor(
                      response.orderId.value,
                      2,
                      "both copies of the replayed event"
                    )
      yield expect.all(
        first.failedSequenceNumbers.isEmpty,
        second.failedSequenceNumbers.isEmpty,
        payloads.size >= 2,
        // Two records on the wire…
        payloads.take(2).distinct.size == 1
        // …carrying one logical event: identical bytes, therefore identical eventId.
      )
    }
  }

  // ---------------------------------------------------------------------------------
  // 4. The repos against a real DynamoDB.
  // ---------------------------------------------------------------------------------

  /** The adapters against the real service rather than a fake.
    *
    * `CustomerRepoDynamo` and `CouponRepoDynamo` decode by hand from `AttributeValue`, and
    * a unit test with a hand-built item can only prove the decoder agrees with the
    * fixture. This proves it agrees with what DynamoDB actually returns — including the
    * `L` of `S` that `stackableWithTiers` is stored as.
    */
  test("the repos resolve the brief's seed data against real DynamoDB") { stack =>
    val customers = CustomerRepoDynamo[App](stack.dynamo, LocalStackResource.customersTable)
    val coupons: CouponRepo[App] =
      CouponRepoDynamo[App](stack.dynamo, LocalStackResource.couponsTable)

    for
      customer <- run(customers.find(CustomerId.unsafe("cust-123")))
      missing  <- run(customers.find(CustomerId.unsafe("cust-does-not-exist")))
      coupon   <- run(coupons.find(CouponCode.unsafe("SUMMER10")))
      unknown  <- run(coupons.find(CouponCode.unsafe("NOPE")))
    yield expect.all(
      customer.exists(_.tier == com.kata.pricing.domain.Tier.Gold),
      customer.flatMap(_.name).contains("Ada Lovelace"),
      missing.isEmpty,
      coupon.exists(c => com.kata.pricing.domain.Percent.value(c.discountPercent) == 10),
      // Stored as a DynamoDB list of strings and decoded back into the domain enum.
      coupon.exists(_.stackableWithTiers.contains(com.kata.pricing.domain.Tier.Gold)),
      // An unknown coupon is absent, which validation turns into COUPON_NOT_FOUND.
      unknown.isEmpty
    )
  }

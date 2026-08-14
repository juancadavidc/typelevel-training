# Phase 7 — Stream Processor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the empty `lambda/` module with a DynamoDB-Streams-triggered processor that publishes an `OrderPriced` event to Kinesis for every priced order, using fs2 with bounded concurrency and deterministic idempotency.

**Architecture:** A pure event type and a deterministic `eventIdFor` live in `domain/`. `lambda/` holds a `KinesisPublisher[F]` algebra, an fs2 pipeline polymorphic in `F[_]`, an AWS interpreter acquired via `Resource`, and a single handler that interprets to `IO`. Reprocessing a record republishes a byte-identical event, so the consumer deduplicates on `eventId` — no dedup table, and the guarantee is provable with property tests.

**Tech Stack:** Scala 3.8.4, cats-effect 3.7.0, fs2 3.13.0, weaver 0.13.0 + ScalaCheck, ciris 3.15.0, AWS SDK v2 (`kinesis`), `aws-lambda-java-core` 1.4.0, `aws-lambda-java-events` 3.16.1.

**Design doc:** `docs/superpowers/specs/2026-08-12-phase-7-stream-processor-design.md`

## Global Constraints

- **`domain/` must never import `cats.effect`, `http4s`, `smithy4s` or `software.amazon`.** Its only dependency is `cats-core`. Verify with the grep in `docs/ROADMAP.md`. (DoD #1)
- **Business logic is polymorphic in `F[_]`; `IO` appears only at the composition root** — here, `StreamProcessorHandler`. (DoD #2)
- **AWS clients are acquired via `Resource`, never opened or closed by hand.** (DoD #5)
- **IDs are opaque types, ADTs are `enum`. No `var`, no shared mutable state.** (DoD #7)
- **Money is `BigDecimal`, never `Double`.** Use the existing `Money` opaque type.
- **The handler's FQN is fixed by already-deployed infrastructure:** `com.kata.pricing.lambda.StreamProcessorHandler::handleRequest`, declared at `cdk/lib/compute-stack.ts:39`. Renaming the class without editing the CDK deploys a Lambda that cannot start.
- **No bare `sys.env(...)`** — configuration loads through ciris, following `service/src/main/scala/com/kata/pricing/service/config/AppConfig.scala`.
- **Scala 3 syntax with `-source:future`** in `domain` and `lambda` (already set in `build.sbt`): indentation-based, `given`/`using`, no `implicit val`.
- `lambda/build.sbt` settings already include `weaverDeps` and the weaver `testFramework`. No build changes are needed except Task 0.

## File Structure

| File | Responsibility |
|---|---|
| `domain/.../OrderPricedEvent.scala` | The event payload + `eventIdFor`. Pure, no effects. |
| `domain/src/test/.../OrderPricedEventSuite.scala` | Determinism and collision properties. |
| `lambda/.../port/KinesisPublisher.scala` | One-operation algebra. No SDK types in its signature. |
| `lambda/.../StreamDecoder.scala` | NEW_IMAGE `java.util.Map` → `OrderPricedEvent`. Pure. |
| `lambda/.../StreamProcessor.scala` | The fs2 pipeline, polymorphic in `F[_]`. |
| `lambda/.../config/ProcessorConfig.scala` | ciris config: stream name, region, endpoint, concurrency. |
| `lambda/.../aws/KinesisPublisherLive.scala` | The AWS interpreter + its `Resource`. |
| `lambda/.../StreamProcessorHandler.scala` | AWS entry point; the only `unsafeRunSync`. |
| `lambda/src/test/.../Fixtures.scala` | Record builders + the in-memory publisher. |
| `lambda/src/test/.../StreamDecoderSuite.scala` | Decoding, including the round-trip against the real writer's shape. |
| `lambda/src/test/.../StreamProcessorSuite.scala` | Pipeline behaviour, failures, concurrency. |

---

### Task 0: Fix the assembly jar name

The CDK expects `stream-processor-assembly.jar` (`cdk/bin/pricing.ts:24`), but the module currently produces `stream-processor-assembly-0.1.0-SNAPSHOT.jar` — sbt-assembly appends the version when `assemblyJarName` is not set. This only bites in non-local mode (LocalStack mounts the directory), but it is a real defect and it is one line.

**Files:**
- Modify: `build.sbt` (the `lambda` project's settings)

**Interfaces:**
- Consumes: nothing.
- Produces: an assembly artifact named exactly `stream-processor-assembly.jar`.

- [ ] **Step 1: Confirm the current mismatch**

Run: `sbt -batch "print lambda/assembly/assemblyJarName"`
Expected: `stream-processor-assembly-0.1.0-SNAPSHOT.jar` — which does not match the CDK.

- [ ] **Step 2: Set the jar name**

In `build.sbt`, inside `lazy val lambda`'s `.settings(...)`, add the `assemblyJarName` line immediately before the existing `assembly / assemblyMergeStrategy`:

```scala
    // Pinned because `cdk/bin/pricing.ts` names this file for the non-local deploy path.
    // Without it sbt-assembly appends the version and CDK cannot find the artifact.
    assembly / assemblyJarName := "stream-processor-assembly.jar",
    assembly / assemblyMergeStrategy := {
```

- [ ] **Step 3: Verify**

Run: `sbt -batch "print lambda/assembly/assemblyJarName"`
Expected: `stream-processor-assembly.jar`

- [ ] **Step 4: Commit**

```bash
git add build.sbt
git commit -m "fix: pin the lambda assembly jar name to match the CDK"
```

---

### Task 1: `OrderPricedEvent` and deterministic identity

The event and the rule that makes it unique. Pure, in `domain/`, so idempotency is provable without an effect runtime.

**Files:**
- Create: `domain/src/main/scala/com/kata/pricing/domain/OrderPricedEvent.scala`
- Create: `domain/src/test/scala/com/kata/pricing/domain/OrderPricedEventSuite.scala`

**Interfaces:**
- Consumes: `OrderId`, `CustomerId`, `Money`, `CouponCode` from `domain/ids.scala`; `PricedOrder` from `domain/model.scala`.
- Produces:
  - `final case class OrderPricedEvent(eventId: String, orderId: OrderId, customerId: CustomerId, subtotal: Money, discountAmount: Money, total: Money, couponApplied: Option[CouponCode], createdAt: Instant)`
  - `OrderPricedEvent.eventIdFor(orderId: OrderId, createdAt: Instant): String`
  - `OrderPricedEvent.from(order: PricedOrder): OrderPricedEvent`
  - `extension (e: OrderPricedEvent) def partitionKey: String`

- [ ] **Step 1: Write the failing tests**

Create `domain/src/test/scala/com/kata/pricing/domain/OrderPricedEventSuite.scala`:

```scala
package com.kata.pricing.domain

import org.scalacheck.Gen
import weaver.*
import weaver.scalacheck.Checkers

import java.time.Instant

/** Identity is a property of a total function, so these are `pureTest`s and properties:
  * no effect runtime is involved in proving the idempotency guarantee.
  *
  * `SimpleIOSuite with Checkers` is the project's convention (see `PricingSuite`) and is
  * what `forall` requires — `Checkers` needs the effect type the suite supplies.
  * `pureTest` still runs without touching a runtime.
  */
object OrderPricedEventSuite extends SimpleIOSuite with Checkers:

  private val orderIdGen: Gen[OrderId] =
    Gen.chooseNum(1, 100000).map(n => OrderId.unsafe(s"order-$n"))

  private val instantGen: Gen[Instant] =
    Gen.chooseNum(0L, 4_000_000_000L).map(Instant.ofEpochSecond)

  test("eventIdFor is deterministic: the same input always yields the same id") {
    forall(for
      id <- orderIdGen
      at <- instantGen
    yield (id, at)) { (id, at) =>
      expect.eql(
        OrderPricedEvent.eventIdFor(id, at),
        OrderPricedEvent.eventIdFor(id, at)
      )
    }
  }

  test("a different orderId yields a different eventId") {
    forall(for
      a  <- orderIdGen
      b  <- orderIdGen
      at <- instantGen
    yield (a, b, at)) { (a, b, at) =>
      expect(
        a.value == b.value ||
          OrderPricedEvent.eventIdFor(a, at) != OrderPricedEvent.eventIdFor(b, at)
      )
    }
  }

  test("a different createdAt yields a different eventId") {
    forall(for
      id <- orderIdGen
      x  <- instantGen
      y  <- instantGen
    yield (id, x, y)) { (id, x, y) =>
      expect(
        x == y || OrderPricedEvent.eventIdFor(id, x) != OrderPricedEvent.eventIdFor(id, y)
      )
    }
  }

  /** The guard on the spec's stated assumption: identity is keyed on orderId and
    * createdAt ALONE. If a future phase adds re-pricing under the same orderId, this
    * test is the one that must be changed deliberately — two distinct prices would
    * otherwise collapse onto a single eventId and the consumer would drop one.
    */
  pureTest("identity ignores every field other than orderId and createdAt") {
    val base    = Fixtures.pricedOrder()
    val repriced = base.copy(total = Money(BigDecimal("999.99")))
    expect.eql(
      OrderPricedEvent.from(base).eventId,
      OrderPricedEvent.from(repriced).eventId
    )
  }

  pureTest("the partition key is the orderId, so a consumer sees one order in order") {
    val event = OrderPricedEvent.from(Fixtures.pricedOrder())
    expect.eql(event.partitionKey, "order-1")
  }

  pureTest("from copies the priced order's money across unchanged") {
    val order = Fixtures.pricedOrder()
    val event = OrderPricedEvent.from(order)
    expect.eql(event.total, order.total) and
      expect.eql(event.subtotal, order.subtotal) and
      expect.eql(event.customerId.value, order.customerId.value)
  }
```

- [ ] **Step 2: Add the `pricedOrder` fixture**

`Fixtures.scala` has no `PricedOrder` builder yet. Append this to `object Fixtures` in `domain/src/test/scala/com/kata/pricing/domain/Fixtures.scala`:

```scala
  def pricedOrder(
      orderId: String = "order-1",
      createdAt: Instant = now
  ): PricedOrder =
    PricedOrder(
      orderId = OrderId.unsafe(orderId),
      customerId = CustomerId.unsafe("cust-123"),
      status = OrderStatus.Priced,
      lines = NonEmptyList.of(
        PricedLine(
          Sku.unsafe("SKU-001"),
          Quantity.unsafe(2),
          Money(BigDecimal("19.99")),
          Money(BigDecimal("39.98"))
        )
      ),
      subtotal = Money(BigDecimal("39.98")),
      discountAmount = Money(BigDecimal("3.99")),
      total = Money(BigDecimal("35.99")),
      couponApplied = Some(CouponCode.unsafe("SUMMER10")),
      createdAt = createdAt
    )
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `sbt "domain/testOnly *OrderPricedEventSuite"`
Expected: FAIL — compilation error, `Not found: OrderPricedEvent`.

- [ ] **Step 4: Write the implementation**

Create `domain/src/main/scala/com/kata/pricing/domain/OrderPricedEvent.scala`:

```scala
package com.kata.pricing.domain

import java.security.MessageDigest
import java.time.Instant

/** The event published when an order has been priced.
  *
  * It carries the complete resulting state, not a delta. That is deliberate: an event
  * that describes "what is now true" can be applied twice with the same outcome, whereas
  * "subtract 3.99" cannot. Combined with the deterministic `eventId` below, this is what
  * makes at-least-once delivery survivable without a deduplication table.
  */
final case class OrderPricedEvent(
    eventId: String,
    orderId: OrderId,
    customerId: CustomerId,
    subtotal: Money,
    discountAmount: Money,
    total: Money,
    couponApplied: Option[CouponCode],
    createdAt: Instant
)

object OrderPricedEvent:

  /** The idempotency key, derived rather than generated.
    *
    * A random UUID here would make every reprocessing look like a new event, which is
    * exactly the failure DynamoDB Streams' at-least-once delivery guarantees will find:
    * a retried batch would double-count. Deriving the id from the order's identity means
    * reprocessing produces a byte-identical event and the consumer can discard it.
    *
    * Keyed on `orderId` + `createdAt` only. `PricedOrder` has no version field and
    * `OrderStatus` has a single case, `Priced` — an order is priced once. If re-pricing
    * is ever added, this function must take the new version field, or two distinct
    * prices for one order would collapse onto one id. `OrderPricedEventSuite` guards it.
    */
  def eventIdFor(orderId: OrderId, createdAt: Instant): String =
    val payload = s"${orderId.value}|${createdAt.toString}"
    val digest  = MessageDigest.getInstance("SHA-256").digest(payload.getBytes("UTF-8"))
    digest.map(byte => f"$byte%02x").mkString

  def from(order: PricedOrder): OrderPricedEvent =
    OrderPricedEvent(
      eventId = eventIdFor(order.orderId, order.createdAt),
      orderId = order.orderId,
      customerId = order.customerId,
      subtotal = order.subtotal,
      discountAmount = order.discountAmount,
      total = order.total,
      couponApplied = order.couponApplied,
      createdAt = order.createdAt
    )

  /** Kinesis orders records within a shard, not across them. Keying by `orderId` puts
    * every event for one order on the same shard, so a consumer sees that order's
    * history in order — which is the guarantee that actually matters here.
    */
  extension (event: OrderPricedEvent) def partitionKey: String = event.orderId.value
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbt "domain/testOnly *OrderPricedEventSuite"`
Expected: PASS, 6 tests.

- [ ] **Step 6: Verify DoD #1 still holds**

Run:
```bash
grep -rnE "^\s*import\s+(cats\.effect|org\.http4s|software\.amazon|smithy4s|com\.disneystreaming)" domain/src/main && echo FAIL || echo OK
```
Expected: `OK`

- [ ] **Step 7: Commit**

```bash
git add domain/src/main/scala/com/kata/pricing/domain/OrderPricedEvent.scala \
        domain/src/test/scala/com/kata/pricing/domain/OrderPricedEventSuite.scala \
        domain/src/test/scala/com/kata/pricing/domain/Fixtures.scala
git commit -m "feat: OrderPricedEvent with a deterministic, derived event id"
```

---

### Task 2: The `KinesisPublisher` algebra and the decoder

The port and the pure translation from a stream record to a domain event. Both are needed before the pipeline, and they are tested together because the decoder's correctness is only meaningful against the shape `OrderRepoDynamo` actually writes.

**Files:**
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/port/KinesisPublisher.scala`
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/StreamDecoder.scala`
- Create: `lambda/src/test/scala/com/kata/pricing/lambda/Fixtures.scala`
- Create: `lambda/src/test/scala/com/kata/pricing/lambda/StreamDecoderSuite.scala`

**Interfaces:**
- Consumes: `OrderPricedEvent`, `OrderId`, `CustomerId`, `Money`, `CouponCode` (Task 1).
- Produces:
  - `trait KinesisPublisher[F[_]] { def publish(event: OrderPricedEvent): F[Unit] }`
  - `StreamDecoder.decode(record: DynamodbStreamRecord): Either[String, Option[OrderPricedEvent]]` — `Right(None)` means "correctly skipped" (a `REMOVE`), `Left` means malformed.
  - Test helpers: `Fixtures.insertRecord(orderId: String, createdAt: String, sequenceNumber: String): DynamodbStreamRecord`, `Fixtures.removeRecord(orderId: String): DynamodbStreamRecord`, `Fixtures.recordingPublisher[F[_]: Sync]: F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])]`

- [ ] **Step 1: Write the failing tests**

Create `lambda/src/test/scala/com/kata/pricing/lambda/StreamDecoderSuite.scala`:

```scala
package com.kata.pricing.lambda

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue
import com.kata.pricing.domain.OrderPricedEvent
import weaver.FunSuite

/** `FunSuite` with no `Checkers`: every case here is a `pureTest`, since decoding is a
  * total function and needs no effect runtime.
  */
object StreamDecoderSuite extends FunSuite:

  pureTest("an INSERT with a full NEW_IMAGE decodes to an event") {
    val record = Fixtures.insertRecord("order-1", "2026-07-22T14:32:00Z", "seq-1")
    StreamDecoder.decode(record) match
      case Right(Some(event)) =>
        expect.eql(event.orderId.value, "order-1") and
          expect.eql(event.customerId.value, "cust-123") and
          expect.eql(event.total.amount, BigDecimal("35.99")) and
          expect.eql(event.couponApplied.map(_.value), Some("SUMMER10"))
      case other => failure(s"expected a decoded event, got $other")
  }

  /** `expect(... == ...)` rather than `expect.eql`: `expect.eql` needs a cats `Eq`, and
    * this project defines none — it compares structurally with `==` everywhere (see
    * `ValidationSuite`). Adding an `Eq` instance to the pure core solely to satisfy a
    * test would invent a convention the codebase does not use. */
  pureTest("a REMOVE is skipped, not an error") {
    expect(StreamDecoder.decode(Fixtures.removeRecord("order-1")) == Right(None))
  }

  pureTest("a MODIFY decodes: a re-priced order is still a priced order") {
    val record = Fixtures.insertRecord("order-2", "2026-07-22T14:32:00Z", "seq-2")
    record.setEventName("MODIFY")
    expect(StreamDecoder.decode(record).exists(_.isDefined))
  }

  pureTest("an order with no coupon decodes with couponApplied empty") {
    val record = Fixtures.insertRecord("order-3", "2026-07-22T14:32:00Z", "seq-3", coupon = None)
    expect(StreamDecoder.decode(record).exists(_.exists(_.couponApplied.isEmpty)))
  }

  /** A missing required attribute is a Left, never a silently-dropped record. Dropping
    * it would mean an order was priced and nobody was ever told. */
  pureTest("a NEW_IMAGE missing orderId is a decode error, not a skip") {
    val record = Fixtures.insertRecord("order-4", "2026-07-22T14:32:00Z", "seq-4")
    record.getDynamodb.getNewImage.remove("orderId")
    expect(StreamDecoder.decode(record).isLeft)
  }

  pureTest("a malformed timestamp is a decode error") {
    val record = Fixtures.insertRecord("order-5", "not-a-timestamp", "seq-5")
    expect(StreamDecoder.decode(record).isLeft)
  }

  pureTest("a malformed money amount is a decode error") {
    val record = Fixtures.insertRecord("order-6", "2026-07-22T14:32:00Z", "seq-6")
    record.getDynamodb.getNewImage.put(
      "total",
      new AttributeValue().withN("not-a-number")
    )
    expect(StreamDecoder.decode(record).isLeft)
  }

  /** The decoder is the mirror of `OrderRepoDynamo.put`. If that writer changes its
    * attribute names, this test is what catches it — otherwise the break only shows up
    * at runtime in LocalStack, as events that silently stop flowing. */
  pureTest("the decoded event matches what OrderPricedEvent.from produces directly") {
    val record = Fixtures.insertRecord("order-7", "2026-07-22T14:32:00Z", "seq-7")
    val direct = OrderPricedEvent.from(
      com.kata.pricing.domain.Fixtures.pricedOrder("order-7")
    )
    StreamDecoder.decode(record) match
      case Right(Some(decoded)) => expect.eql(decoded.eventId, direct.eventId)
      case other                => failure(s"expected a decoded event, got $other")
  }
```

Note: this suite depends on `Fixtures` (next step) and on `domain`'s `Fixtures.pricedOrder` from Task 1. That cross-module test dependency does **not** work by default — see the next step.

- [ ] **Step 2: Export `domain`'s test classes to `lambda`**

`lambda` declares `.dependsOn(domain)`, which shares only `compile` classes. `domain`'s test fixtures are invisible from `lambda`'s tests, and the last test in the suite above needs `domain.Fixtures.pricedOrder`. Verified: without this change the compiler reports `value Fixtures is not a member of com.kata.pricing.domain`.

In `build.sbt`, change `lazy val lambda`'s dependency line:

```scala
  .dependsOn(domain % "compile->compile;test->test")
```

Reusing the domain's fixture is the right call rather than duplicating a `PricedOrder` builder here: the round-trip test is only meaningful if both sides are built from the *same* source of truth.

- [ ] **Step 3: Write the test fixtures**

Create `lambda/src/test/scala/com/kata/pricing/lambda/Fixtures.scala`:

```scala
package com.kata.pricing.lambda

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{AttributeValue, StreamRecord}
import com.kata.pricing.domain.OrderPricedEvent
import com.kata.pricing.lambda.port.KinesisPublisher

import scala.jdk.CollectionConverters.*

/** Builders for the Java event types AWS hands the handler. They are mutable POJOs, so
  * construction is unavoidably imperative — it is confined here rather than spread
  * across the suites.
  */
object Fixtures:

  private def s(value: String): AttributeValue = new AttributeValue().withS(value)
  private def n(value: String): AttributeValue = new AttributeValue().withN(value)

  /** Mirrors exactly the attribute map written by
    * `service/.../dynamo/OrderRepoDynamo.scala`. Keep the two in step. */
  def newImage(
      orderId: String,
      createdAt: String,
      coupon: Option[String] = Some("SUMMER10")
  ): java.util.Map[String, AttributeValue] =
    val items = new AttributeValue().withL(
      new AttributeValue().withM(
        Map(
          "sku"       -> s("SKU-001"),
          "quantity"  -> n("2"),
          "unitPrice" -> n("19.99"),
          "lineTotal" -> n("39.98")
        ).asJava
      )
    )

    val base = Map(
      "orderId"        -> s(orderId),
      "customerId"     -> s("cust-123"),
      "status"         -> s("PRICED"),
      "items"          -> items,
      "subtotal"       -> n("39.98"),
      "discountAmount" -> n("3.99"),
      "total"          -> n("35.99"),
      "createdAt"      -> s(createdAt),
      "updatedAt"      -> s(createdAt)
    ) ++ coupon.map(code => "couponCode" -> s(code))

    new java.util.HashMap(base.asJava)

  def insertRecord(
      orderId: String,
      createdAt: String = "2026-07-22T14:32:00Z",
      sequenceNumber: String = "seq-1",
      coupon: Option[String] = Some("SUMMER10")
  ): DynamodbStreamRecord =
    val stream = new StreamRecord()
    stream.setNewImage(newImage(orderId, createdAt, coupon))
    stream.setSequenceNumber(sequenceNumber)

    val record = new DynamodbStreamRecord()
    record.setEventName("INSERT")
    record.setDynamodb(stream)
    record

  def removeRecord(orderId: String, sequenceNumber: String = "seq-x"): DynamodbStreamRecord =
    val stream = new StreamRecord()
    stream.setSequenceNumber(sequenceNumber)
    stream.setKeys(Map("orderId" -> s(orderId)).asJava)

    val record = new DynamodbStreamRecord()
    record.setEventName("REMOVE")
    record.setDynamodb(stream)
    record

  def event(records: DynamodbStreamRecord*): DynamodbEvent =
    val e = new DynamodbEvent()
    e.setRecords(records.toList.asJava)
    e

  /** An in-memory publisher: a `Ref`, not a mocking framework. Returns the publisher and
    * a way to read what it received. */
  def recordingPublisher[F[_]: Sync]: F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])] =
    Ref[F].of(Vector.empty[OrderPricedEvent]).map { ref =>
      val publisher = new KinesisPublisher[F]:
        def publish(event: OrderPricedEvent): F[Unit] = ref.update(_ :+ event)
      (publisher, ref.get)
    }

  /** A publisher that fails on the nth event (1-based) and records the rest. */
  def failingPublisher[F[_]: Sync](
      failOn: Int,
      error: Throwable
  ): F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])] =
    Ref[F].of(Vector.empty[OrderPricedEvent]).map { ref =>
      val publisher = new KinesisPublisher[F]:
        def publish(event: OrderPricedEvent): F[Unit] =
          ref.updateAndGet(_ :+ event).flatMap { seen =>
            Sync[F].raiseError(error).whenA(seen.size == failOn)
          }
      (publisher, ref.get)
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `sbt "lambda/testOnly *StreamDecoderSuite"`
Expected: FAIL — compilation error, `Not found: StreamDecoder`.

- [ ] **Step 5: Write the algebra**

Create `lambda/src/main/scala/com/kata/pricing/lambda/port/KinesisPublisher.scala`:

```scala
package com.kata.pricing.lambda.port

import com.kata.pricing.domain.OrderPricedEvent

/** The one thing the processor needs from the outside world.
  *
  * No SDK type appears in this signature, which is what lets the pipeline be tested with
  * a `Ref` instead of a running Kinesis — and what would let the target be swapped for
  * SNS or EventBridge without touching the pipeline.
  */
trait KinesisPublisher[F[_]]:
  def publish(event: OrderPricedEvent): F[Unit]
```

- [ ] **Step 6: Write the decoder**

Create `lambda/src/main/scala/com/kata/pricing/lambda/StreamDecoder.scala`:

```scala
package com.kata.pricing.lambda

import cats.syntax.all.*
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue
import com.kata.pricing.domain.*

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Turns a raw stream record into a domain event, or explains why it cannot.
  *
  * Pure and total: every failure is a `Left`, nothing throws. The three-way return says
  * something the pipeline needs to distinguish — `Right(None)` is "correctly skipped"
  * (a REMOVE, which is not a pricing event), while `Left` is "this should have decoded
  * and did not", which must not pass silently.
  *
  * This is the mirror image of `OrderRepoDynamo.put`. The attribute names are a contract
  * between the two, enforced by a test rather than by hope.
  */
object StreamDecoder:

  private val Priced = Set("INSERT", "MODIFY")

  def decode(record: DynamodbStreamRecord): Either[String, Option[OrderPricedEvent]] =
    Option(record.getEventName) match
      case Some(name) if Priced.contains(name) => decodeImage(record).map(Some(_))
      case Some(_)                             => Right(None)
      case None                                => Left("record has no eventName")

  private def decodeImage(record: DynamodbStreamRecord): Either[String, OrderPricedEvent] =
    for
      stream <- Option(record.getDynamodb).toRight("record has no dynamodb payload")
      image  <- Option(stream.getNewImage).map(_.asScala.toMap).toRight("record has no NEW_IMAGE")
      order  <- toEvent(image)
    yield order

  private def toEvent(
      image: Map[String, AttributeValue]
  ): Either[String, OrderPricedEvent] =
    for
      rawOrderId    <- string(image, "orderId")
      orderId       <- OrderId.from(rawOrderId)
      rawCustomerId <- string(image, "customerId")
      customerId    <- CustomerId.from(rawCustomerId)
      subtotal      <- money(image, "subtotal")
      discount      <- money(image, "discountAmount")
      total         <- money(image, "total")
      createdAt     <- instant(image, "createdAt")
      coupon        <- optionalCoupon(image)
    yield OrderPricedEvent(
      eventId = OrderPricedEvent.eventIdFor(orderId, createdAt),
      orderId = orderId,
      customerId = customerId,
      subtotal = subtotal,
      discountAmount = discount,
      total = total,
      couponApplied = coupon,
      createdAt = createdAt
    )

  private def string(image: Map[String, AttributeValue], key: String): Either[String, String] =
    image.get(key).flatMap(attr => Option(attr.getS)).toRight(s"missing string attribute '$key'")

  private def money(image: Map[String, AttributeValue], key: String): Either[String, Money] =
    for
      raw    <- image.get(key).flatMap(attr => Option(attr.getN)).toRight(s"missing numeric attribute '$key'")
      parsed <- Try(BigDecimal(raw)).toEither.leftMap(_ => s"attribute '$key' is not a number: '$raw'")
      value  <- Money.from(parsed)
    yield value

  private def instant(image: Map[String, AttributeValue], key: String): Either[String, Instant] =
    string(image, key).flatMap { raw =>
      Try(Instant.parse(raw)).toEither.leftMap(_ => s"attribute '$key' is not an instant: '$raw'")
    }

  /** Absent is legitimate — an order without a coupon. Present but malformed is not. */
  private def optionalCoupon(
      image: Map[String, AttributeValue]
  ): Either[String, Option[CouponCode]] =
    image.get("couponCode").flatMap(attr => Option(attr.getS)) match
      case None       => Right(None)
      case Some(code) => CouponCode.from(code).map(Some(_))
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `sbt "lambda/testOnly *StreamDecoderSuite"`
Expected: PASS, 8 tests.

- [ ] **Step 8: Commit**

```bash
git add build.sbt \
        lambda/src/main/scala/com/kata/pricing/lambda/port/KinesisPublisher.scala \
        lambda/src/main/scala/com/kata/pricing/lambda/StreamDecoder.scala \
        lambda/src/test/scala/com/kata/pricing/lambda/Fixtures.scala \
        lambda/src/test/scala/com/kata/pricing/lambda/StreamDecoderSuite.scala
git commit -m "feat: the KinesisPublisher algebra and the NEW_IMAGE decoder"
```

---

### Task 3: The fs2 pipeline

The core of the phase: a stream pipeline with bounded concurrency, polymorphic in `F[_]`, that reports which records were not processed.

**Files:**
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessor.scala`
- Create: `lambda/src/test/scala/com/kata/pricing/lambda/StreamProcessorSuite.scala`

**Interfaces:**
- Consumes: `KinesisPublisher[F]`, `StreamDecoder.decode` (Task 2); `OrderPricedEvent` (Task 1).
- Produces:
  - `final case class ProcessResult(failedSequenceNumbers: List[String])`
  - `final class StreamProcessor[F[_]: Async](publisher: KinesisPublisher[F], concurrency: Int)`
  - `def process(records: List[DynamodbStreamRecord]): F[ProcessResult]`

- [ ] **Step 1: Write the failing tests**

Create `lambda/src/test/scala/com/kata/pricing/lambda/StreamProcessorSuite.scala`:

```scala
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt "lambda/testOnly *StreamProcessorSuite"`
Expected: FAIL — compilation error, `Not found: StreamProcessor`.

- [ ] **Step 3: Write the implementation**

Create `lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessor.scala`:

```scala
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "lambda/testOnly *StreamProcessorSuite"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessor.scala \
        lambda/src/test/scala/com/kata/pricing/lambda/StreamProcessorSuite.scala
git commit -m "feat: the fs2 batch pipeline with bounded concurrency and honest failure reporting"
```

---

### Task 4: Config, the Kinesis interpreter, and the handler

The composition root: ciris config, the SDK client in a `Resource`, and the entry point whose name the CDK already declares. There are no unit tests for the live publisher — it is exercised against LocalStack in phase 9, which is where an SDK client belongs.

**Files:**
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/config/ProcessorConfig.scala`
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/aws/KinesisPublisherLive.scala`
- Create: `lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessorHandler.scala`
- Create: `lambda/src/test/scala/com/kata/pricing/lambda/config/ProcessorConfigSuite.scala`

**Interfaces:**
- Consumes: `StreamProcessor`, `ProcessResult` (Task 3); `KinesisPublisher` (Task 2).
- Produces:
  - `final case class ProcessorConfig(region: Region, endpointOverride: Option[URI], streamName: String, concurrency: Int)`
  - `ProcessorConfig.load[F[_]: Async]: F[ProcessorConfig]`
  - `KinesisPublisherLive.resource[F[_]: Async](config: ProcessorConfig): Resource[F, KinesisPublisher[F]]`
  - `class StreamProcessorHandler extends RequestHandler[DynamodbEvent, StreamsEventResponse]`

- [ ] **Step 1: Write the failing config test**

Create `lambda/src/test/scala/com/kata/pricing/lambda/config/ProcessorConfigSuite.scala`:

```scala
package com.kata.pricing.lambda.config

import cats.effect.IO
import weaver.SimpleIOSuite

object ProcessorConfigSuite extends SimpleIOSuite:

  /** The defaults must load with no environment set, or a bare `sbt lambda/test` would
    * depend on the developer's shell. The CDK injects the real values. */
  test("the config loads from defaults when no environment is set") {
    ProcessorConfig.load[IO].map { config =>
      expect.eql(config.streamName, "order-priced-events") and
        expect.eql(config.concurrency, 4) and
        expect.eql(config.region.id, "us-east-1")
    }
  }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt "lambda/testOnly *ProcessorConfigSuite"`
Expected: FAIL — compilation error, `Not found: ProcessorConfig`.

- [ ] **Step 3: Add the ciris and Kinesis dependencies**

`lambda` has `kinesis` already but not `ciris`. In `build.sbt`, inside `lazy val lambda`'s `libraryDependencies`, add:

```scala
      "is.cir"                       %% "ciris"                  % cirisVersion,
```

- [ ] **Step 4: Write the config**

Create `lambda/src/main/scala/com/kata/pricing/lambda/config/ProcessorConfig.scala`:

```scala
package com.kata.pricing.lambda.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import software.amazon.awssdk.regions.Region

import java.net.URI

/** Typed configuration, matching `service`'s `AppConfig`: no bare `sys.env(...)`.
  *
  * `KINESIS_STREAM_NAME` is injected by the CDK (`compute-stack.ts`), and
  * `AWS_ENDPOINT_URL` is present only under LocalStack. The defaults exist so the test
  * suite does not depend on a configured shell.
  */
final case class ProcessorConfig(
    region: Region,
    endpointOverride: Option[URI],
    streamName: String,
    concurrency: Int
)

object ProcessorConfig:

  /** Bounded publish concurrency, configurable rather than a literal buried in the
    * pipeline: the right value depends on the Kinesis shard count, which is deployment
    * configuration, not a property of the code. */
  private val concurrency: ConfigValue[Effect, Int] =
    env("PUBLISH_CONCURRENCY").default("4").as[Int]

  def load[F[_]: Async]: F[ProcessorConfig] =
    (
      env("AWS_REGION").default("us-east-1").map(Region.of),
      env("AWS_ENDPOINT_URL").option.map(_.map(URI.create)),
      env("KINESIS_STREAM_NAME").default("order-priced-events"),
      concurrency
    ).parMapN(ProcessorConfig.apply).load[F]
```

- [ ] **Step 5: Run the config test to verify it passes**

Run: `sbt "lambda/testOnly *ProcessorConfigSuite"`
Expected: PASS, 1 test.

- [ ] **Step 6: Write the Kinesis interpreter**

Create `lambda/src/main/scala/com/kata/pricing/lambda/aws/KinesisPublisherLive.scala`:

```scala
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
```

- [ ] **Step 7: Write the handler**

Create `lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessorHandler.scala`:

```scala
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
```

Note: the builders are Lombok-generated; if `withItemIdentifier`/`withBatchItemFailures` do not resolve, use the setters instead:
```scala
    val response = new StreamsEventResponse()
    response.setBatchItemFailures(failures.asJava)
    response
```

- [ ] **Step 8: Verify the whole module compiles and all tests pass**

Run: `sbt lambda/test`
Expected: PASS — all suites green.

- [ ] **Step 9: Verify the handler name matches the CDK**

Run:
```bash
grep -n 'handler:' cdk/lib/compute-stack.ts
grep -rn 'class StreamProcessorHandler' lambda/src/main
```
Expected: the FQN in the CDK is `com.kata.pricing.lambda.StreamProcessorHandler::handleRequest` and the class is in package `com.kata.pricing.lambda`.

- [ ] **Step 10: Verify the assembly builds**

Run: `sbt lambda/assembly`
Expected: SUCCESS, producing `lambda/target/scala-3.8.4/stream-processor-assembly.jar`.

- [ ] **Step 11: Commit**

```bash
git add build.sbt lambda/src/main/scala/com/kata/pricing/lambda/config/ProcessorConfig.scala \
        lambda/src/main/scala/com/kata/pricing/lambda/aws/KinesisPublisherLive.scala \
        lambda/src/main/scala/com/kata/pricing/lambda/StreamProcessorHandler.scala \
        lambda/src/test/scala/com/kata/pricing/lambda/config/ProcessorConfigSuite.scala
git commit -m "feat: ciris config, the Kinesis interpreter via Resource, and the handler"
```

---

### Task 5: Full verification and the roadmap update

**Files:**
- Modify: `docs/ROADMAP.md`

**Interfaces:**
- Consumes: everything from Tasks 0–4.
- Produces: a verified-green build and an accurate roadmap.

- [ ] **Step 1: Run the full unit suite**

Run: `make test`
Expected: PASS. `domain` gains 6 tests, `lambda` goes from 0 to 17. Confirm `lambda` now reports a non-zero total — the whole point of the phase was that `sbt test` walked an empty module.

- [ ] **Step 2: Verify DoD #1**

Run:
```bash
grep -rnE "^\s*import\s+(cats\.effect|org\.http4s|software\.amazon|smithy4s|com\.disneystreaming)" domain/src/main && echo FAIL || echo OK
```
Expected: `OK`

- [ ] **Step 3: Verify DoD #7 — no `var`, no mutable state in the new code**

Run: `grep -rn '\bvar\b' domain/src/main lambda/src/main`
Expected: no matches. (The Java POJO builders in `lambda/src/test/.../Fixtures.scala` are mutable by necessity — that is test-only and confined to the fixture builders.)

- [ ] **Step 4: Update the roadmap**

In `docs/ROADMAP.md`, set phase 7's status to `✅ done` with a link to the design doc, matching the format phase 3 uses:

```markdown
| 7 | Lambda: DynamoDB Streams → fs2 → Kinesis, idempotent | ✅ done — [design](superpowers/specs/2026-08-12-phase-7-stream-processor-design.md) |
```

Then update the Definition of Done table rows that this phase closes:

```markdown
| 4 | Order write + event emission — **see the contradiction below** | ✅ | CDC via Streams; `StreamProcessor` + deterministic `eventId` |
| 5 | DynamoDB/Kinesis clients acquired via `Resource`, never opened/closed by hand | ✅ | `KinesisPublisherLive.resource`, phase 5 for DynamoDB |
```

- [ ] **Step 5: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs: mark phase 7 done and close DoD #4 and #5"
```

---

## Verification Summary

| DoD rule | How this plan satisfies it | Where |
|---|---|---|
| #1 pure core | `OrderPricedEvent` uses only `cats-core` + JDK; grep verified in Task 1 Step 6 | Task 1 |
| #2 polymorphic `F[_]` | `StreamProcessor[F[_]: Async]`; `IO` only in the handler | Tasks 3, 4 |
| #4 write + emission | CDC via Streams; idempotency by deterministic `eventId`, tested | Tasks 1, 3 |
| #5 `Resource` | `KinesisPublisherLive.resource`, allocated once per container | Task 4 |
| #7 opaque types, no `var` | The event reuses the domain's opaque types; grep verified | Task 5 |
| fs2 bounded concurrency | `parEvalMap(concurrency)`, asserted by an in-flight-peak test | Task 3 |

**Not covered here, by design:** the live Kinesis interpreter has no unit test — it is exercised against LocalStack in phase 9 (`make test-integration`), together with the end-to-end flow `POST /orders/price` → DynamoDB → Streams → Lambda → Kinesis.

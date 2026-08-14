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

  private given cats.Show[OrderId]  = cats.Show.fromToString
  private given cats.Show[Instant]  = cats.Show.fromToString

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
    expect.all(
      event.total == order.total,
      event.subtotal == order.subtotal,
      event.customerId.value == order.customerId.value
    )
  }

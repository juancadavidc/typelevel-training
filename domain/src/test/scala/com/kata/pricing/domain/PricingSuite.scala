package com.kata.pricing.domain

import cats.data.NonEmptyList
import weaver.*
import weaver.scalacheck.Checkers

import com.kata.pricing.domain.Fixtures.*

/** `pureTest` rather than `test`: there is no `IO` anywhere because there is nothing to
  * run. That this whole suite can be `pureTest` is living proof that the core is pure —
  * the day `test` becomes necessary would be the sign that an effect leaked into the
  * layer that must not have any.
  */
object PricingSuite extends SimpleIOSuite with Checkers:

  private val orderId = OrderId.unsafe("ord-9f2c9b7a")

  // weaver requires `Show` for the generated type: when a property fails it prints the
  // concrete counterexample. Without this you would get "property failed" and no data to
  // reproduce it with. `fromToString` suffices because these are case classes.
  private given cats.Show[ValidOrder] = cats.Show.fromToString
  private given cats.Show[Perk]       = cats.Show.fromToString

  private def lines(items: (String, Int, String)*): NonEmptyList[OrderLine] =
    NonEmptyList.fromListUnsafe(
      items.toList.map((sku, quantity, price) =>
        OrderLine(Sku.unsafe(sku), Quantity.unsafe(quantity), Money(BigDecimal(price)))
      )
    )

  pureTest("reproduces the brief's exact example") {
    val order = ValidOrder(
      customer(),
      lines(("SKU-001", 2, "19.99"), ("SKU-045", 1, "49.99")),
      Some(coupon(percent = 10))
    )

    val priced = Pricing.price(order, perk = None, orderId, now)

    // expect.all accumulates: if three assertions fail, all three are reported.
    expect.all(
      priced.subtotal.amount == BigDecimal("89.97"),
      priced.discountAmount.amount == BigDecimal("8.99"),
      priced.total.amount == BigDecimal("80.98"),
      priced.couponApplied.map(_.value).contains("SUMMER10"),
      priced.status == OrderStatus.Priced
    )
  }

  pureTest("a 100% coupon leaves the total at exactly zero, not negative nor -0.00") {
    val order = ValidOrder(customer(), lines(("SKU-001", 1, "19.99")), Some(coupon(percent = 100)))
    val priced = Pricing.price(order, perk = None, orderId, now)

    expect.all(
      priced.total.amount == BigDecimal("0.00"),
      priced.total.amount.signum >= 0
    )
  }

  pureTest("coupon and perk together cannot push the total below zero") {
    val order  = ValidOrder(customer(), lines(("SKU-001", 1, "19.99")), Some(coupon(percent = 80)))
    val priced = Pricing.price(order, Some(Perk(Percent.unsafe(80))), orderId, now)

    expect.all(
      priced.total.amount == BigDecimal("0.00"),
      priced.discountAmount.amount == priced.subtotal.amount
    )
  }

  pureTest("the subtotal is the sum of the line totals") {
    val order  = ValidOrder(customer(), lines(("SKU-001", 3, "19.99"), ("SKU-100", 2, "5.00")), None)
    val priced = Pricing.price(order, perk = None, orderId, now)

    val sumOfLines = priced.lines.foldLeft(BigDecimal(0))(_ + _.lineTotal.amount)
    expect(priced.subtotal.amount == sumOfLines)
  }

  // The two properties the brief names. They are domain invariants, not a
  // reimplementation of the calculation — a test that recomputes the price with the same
  // formula proves nothing, it only duplicates the bug if there is one.
  test("property: the total is never negative and never exceeds the subtotal") {
    forall(Fixtures.validOrderGen) { order =>
      val priced = Pricing.price(order, perk = None, orderId, now)
      expect.all(
        priced.total.amount.signum >= 0,
        priced.total.amount <= priced.subtotal.amount,
        priced.discountAmount.amount <= priced.subtotal.amount
      )
    }
  }

  test("property: the invariant holds for any combination of coupon and perk") {
    val combined = for
      order <- Fixtures.validOrderGen
      perk  <- Fixtures.perkGen
    yield (order, perk)

    forall(combined) { (order, perk) =>
      val priced = Pricing.price(order, perk, orderId, now)
      expect.all(
        priced.total.amount.signum >= 0,
        priced.total.amount <= priced.subtotal.amount,
        priced.total.amount == priced.subtotal.amount - priced.discountAmount.amount
      )
    }
  }

  test("property: every money amount comes out with scale 2") {
    forall(Fixtures.validOrderGen) { order =>
      val priced = Pricing.price(order, perk = None, orderId, now)
      expect.all(
        priced.subtotal.amount.scale == 2,
        priced.discountAmount.amount.scale == 2,
        priced.total.amount.scale == 2
      )
    }
  }

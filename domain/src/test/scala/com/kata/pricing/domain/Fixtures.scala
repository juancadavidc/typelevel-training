package com.kata.pricing.domain

import cats.data.NonEmptyList
import org.scalacheck.Gen

import java.time.Instant

/** Fixtures and generators. No `IO` anywhere: the core is pure, so the tests need no
  * effect runtime. That is the concrete payoff of DoD rule 1.
  */
object Fixtures:

  val now: Instant = Instant.parse("2026-07-22T14:32:00Z")

  val catalog: Catalog = Catalog.of(
    "SKU-001" -> BigDecimal("19.99"),
    "SKU-045" -> BigDecimal("49.99"),
    "SKU-100" -> BigDecimal("5.00")
  )

  def customer(tier: Tier = Tier.Gold): Customer =
    Customer(CustomerId.unsafe("cust-123"), tier, Some("Ada"), now.minusSeconds(86_400))

  def coupon(
      code: String = "SUMMER10",
      percent: Int = 10,
      minOrderAmount: BigDecimal = BigDecimal(0),
      usageLimit: Int = 100,
      usageCount: Int = 0,
      expiresAt: Instant = now.plusSeconds(86_400),
      tiers: Set[Tier] = Tier.values.toSet
  ): Coupon =
    Coupon(
      CouponCode.unsafe(code),
      Percent.unsafe(percent),
      Money(minOrderAmount),
      usageLimit,
      usageCount,
      expiresAt,
      tiers
    )

  /** Money generators with scale 2 and a bounded range.
    *
    * Why not the stock `Arbitrary[BigDecimal]`: it produces values with hundreds of
    * digits and arbitrary scales that fail the tests through scale overflow rather than
    * through the logic under test. A *realistic* money generator finds real bugs; a
    * pathological one only finds noise.
    */
  val moneyGen: Gen[Money] =
    Gen.choose(0L, 1_000_000L).map(cents => Money(BigDecimal(cents) / 100))

  val quantityGen: Gen[Quantity] = Gen.choose(1, 50).map(Quantity.unsafe)

  val percentGen: Gen[Percent] = Gen.choose(0, 100).map(Percent.unsafe)

  val orderLineGen: Gen[OrderLine] =
    for
      sku      <- Gen.oneOf("SKU-001", "SKU-045", "SKU-100").map(Sku.unsafe)
      quantity <- quantityGen
      price    <- moneyGen
    yield OrderLine(sku, quantity, price)

  val linesGen: Gen[NonEmptyList[OrderLine]] =
    Gen.choose(1, 8).flatMap(n => Gen.listOfN(n, orderLineGen)).map { list =>
      NonEmptyList.fromListUnsafe(list.take(8).ensuring(_.nonEmpty))
    }

  val couponGen: Gen[Option[Coupon]] =
    Gen.option(percentGen.map(p => coupon(percent = Percent.value(p))))

  val perkGen: Gen[Option[Perk]] = Gen.option(percentGen.map(Perk.apply))

  val validOrderGen: Gen[ValidOrder] =
    for
      tier   <- Gen.oneOf(Tier.values.toList)
      lines  <- linesGen
      coupon <- couponGen
    yield ValidOrder(customer(tier), lines, coupon)

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

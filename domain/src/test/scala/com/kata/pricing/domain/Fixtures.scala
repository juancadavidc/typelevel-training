package com.kata.pricing.domain

import cats.data.NonEmptyList
import org.scalacheck.Gen

import java.time.Instant

/** Fixtures y generadores. Ningún `IO` aquí: el núcleo es puro, así que los tests no
  * necesitan runtime de efectos. Ese es el beneficio concreto de la regla 1 del DoD.
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

  /** Generadores de dinero con escala 2 y rango acotado.
    *
    * Por qué no `Arbitrary[BigDecimal]` de serie: genera valores con cientos de dígitos
    * y escalas arbitrarias que hacen fallar los tests por desbordamiento de escala, no
    * por la lógica que se quería probar. Un generador de dinero *realista* encuentra
    * bugs reales; uno patológico sólo encuentra ruido.
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

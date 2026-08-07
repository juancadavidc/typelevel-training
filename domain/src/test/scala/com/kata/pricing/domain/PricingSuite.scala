package com.kata.pricing.domain

import cats.data.NonEmptyList
import weaver.*
import weaver.scalacheck.Checkers

import com.kata.pricing.domain.Fixtures.*

/** `pureTest` en lugar de `test`: no hay `IO` en ninguna parte porque no hay nada que
  * ejecutar. Que toda esta suite pueda ser `pureTest` es la prueba viva de que el
  * núcleo es puro — si un día hiciera falta `test`, sería señal de que un efecto se
  * coló en la capa que no debe tenerlos.
  */
object PricingSuite extends SimpleIOSuite with Checkers:

  private val orderId = OrderId.unsafe("ord-9f2c9b7a")

  // weaver exige `Show` para el tipo generado: cuando una propiedad falla, imprime el
  // contraejemplo concreto. Sin esto tendrías "property failed" y ningún dato con el
  // que reproducir. `fromToString` basta porque son case classes.
  private given cats.Show[ValidOrder] = cats.Show.fromToString
  private given cats.Show[Perk]       = cats.Show.fromToString

  private def lines(items: (String, Int, String)*): NonEmptyList[OrderLine] =
    NonEmptyList.fromListUnsafe(
      items.toList.map((sku, quantity, price) =>
        OrderLine(Sku.unsafe(sku), Quantity.unsafe(quantity), Money(BigDecimal(price)))
      )
    )

  pureTest("reproduce el ejemplo exacto del PDF") {
    val order = ValidOrder(
      customer(),
      lines(("SKU-001", 2, "19.99"), ("SKU-045", 1, "49.99")),
      Some(coupon(percent = 10))
    )

    val priced = Pricing.price(order, perk = None, orderId, now)

    // expect.all acumula: si fallan tres aserciones, se ven las tres.
    expect.all(
      priced.subtotal.amount == BigDecimal("89.97"),
      priced.discountAmount.amount == BigDecimal("8.99"),
      priced.total.amount == BigDecimal("80.98"),
      priced.couponApplied.map(_.value).contains("SUMMER10"),
      priced.status == OrderStatus.Priced
    )
  }

  pureTest("un cupón del 100% deja el total en cero exacto, no en negativo ni en -0.00") {
    val order = ValidOrder(customer(), lines(("SKU-001", 1, "19.99")), Some(coupon(percent = 100)))
    val priced = Pricing.price(order, perk = None, orderId, now)

    expect.all(
      priced.total.amount == BigDecimal("0.00"),
      priced.total.amount.signum >= 0
    )
  }

  pureTest("cupón y perk juntos no pueden empujar el total por debajo de cero") {
    val order  = ValidOrder(customer(), lines(("SKU-001", 1, "19.99")), Some(coupon(percent = 80)))
    val priced = Pricing.price(order, Some(Perk(Percent.unsafe(80))), orderId, now)

    expect.all(
      priced.total.amount == BigDecimal("0.00"),
      priced.discountAmount.amount == priced.subtotal.amount
    )
  }

  pureTest("el subtotal es la suma de los line totals") {
    val order  = ValidOrder(customer(), lines(("SKU-001", 3, "19.99"), ("SKU-100", 2, "5.00")), None)
    val priced = Pricing.price(order, perk = None, orderId, now)

    val sumOfLines = priced.lines.foldLeft(BigDecimal(0))(_ + _.lineTotal.amount)
    expect(priced.subtotal.amount == sumOfLines)
  }

  // Las dos propiedades que nombra el PDF. Son invariantes del dominio, no una
  // reimplementación del cálculo — un test que recalcula el precio con la misma
  // fórmula no prueba nada, sólo duplica el bug si lo hay.
  test("propiedad: el total nunca es negativo y nunca supera el subtotal") {
    forall(Fixtures.validOrderGen) { order =>
      val priced = Pricing.price(order, perk = None, orderId, now)
      expect.all(
        priced.total.amount.signum >= 0,
        priced.total.amount <= priced.subtotal.amount,
        priced.discountAmount.amount <= priced.subtotal.amount
      )
    }
  }

  test("propiedad: la invariante se mantiene con cualquier combinación de cupón y perk") {
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

  test("propiedad: todo importe de dinero sale con escala 2") {
    forall(Fixtures.validOrderGen) { order =>
      val priced = Pricing.price(order, perk = None, orderId, now)
      expect.all(
        priced.subtotal.amount.scale == 2,
        priced.discountAmount.amount.scale == 2,
        priced.total.amount.scale == 2
      )
    }
  }

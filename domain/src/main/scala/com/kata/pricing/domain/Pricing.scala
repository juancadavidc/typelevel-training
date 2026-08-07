package com.kata.pricing.domain

import java.time.Instant

/** El cálculo de precio: una función total, determinista y sin efectos.
  *
  * `orderId` y `now` entran como parámetros en vez de generarse aquí dentro. Parece un
  * detalle, pero es lo que hace que esta función sea testeable sin `IO` y que los
  * property tests de ScalaCheck sean reproducibles: generar un UUID o leer el reloj
  * son efectos, y viven en la capa de servicio.
  *
  * El perk del partner llega por separado de `ValidOrder` porque tiene otra naturaleza:
  * la validación describe la petición, el perk es un enriquecimiento opcional que
  * procede de un sistema externo poco fiable. Que sea `Option` en la firma es lo que
  * obliga al llamador a decidir qué hacer cuando el partner falla.
  */
object Pricing:

  def price(order: ValidOrder, perk: Option[Perk], orderId: OrderId, now: Instant): PricedOrder =
    val lines = order.lines.map(line =>
      PricedLine(line.sku, line.quantity, line.unitPrice, line.lineTotal)
    )

    val subtotal = lines.foldLeft(Money.zero)((accumulated, line) => accumulated.plus(line.lineTotal))

    val couponDiscount = order.coupon.fold(Money.zero)(c => subtotal.percentOf(c.discountPercent))
    val perkDiscount   = perk.fold(Money.zero)(p => subtotal.percentOf(p.extraDiscountPercent))

    // Los dos descuentos se suman y luego se acotan al subtotal. Acotar aquí, y no
    // confiar en que 10% + 15% nunca pase de 100, es lo que sostiene la propiedad
    // "el total nunca es negativo" sea cual sea la combinación de cupón y perk.
    val discountAmount = capAt(couponDiscount.plus(perkDiscount), subtotal)

    PricedOrder(
      orderId = orderId,
      customerId = order.customer.id,
      status = OrderStatus.Priced,
      lines = lines,
      subtotal = subtotal,
      discountAmount = discountAmount,
      total = subtotal.minusFloored(discountAmount),
      couponApplied = order.coupon.map(_.code),
      createdAt = now
    )

  private def capAt(value: Money, ceiling: Money): Money =
    if value.isAtLeast(ceiling) then ceiling else value

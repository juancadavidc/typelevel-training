package com.kata.pricing.domain

import java.time.Instant

/** The price calculation: a total, deterministic, effect-free function.
  *
  * `orderId` and `now` come in as parameters instead of being generated in here. It
  * looks like a detail, but it is what makes this function testable without `IO` and the
  * ScalaCheck property tests reproducible: generating a UUID or reading the clock are
  * effects, and they live in the service layer.
  *
  * The partner perk arrives separately from `ValidOrder` because it has a different
  * nature: validation describes the request, while the perk is an optional enrichment
  * coming from an unreliable external system. Its being an `Option` in the signature is
  * what forces the caller to decide what to do when the partner fails.
  */
object Pricing:

  def price(order: ValidOrder, perk: Option[Perk], orderId: OrderId, now: Instant): PricedOrder =
    val lines = order.lines.map(line =>
      PricedLine(line.sku, line.quantity, line.unitPrice, line.lineTotal)
    )

    val subtotal = lines.foldLeft(Money.zero)((accumulated, line) => accumulated.plus(line.lineTotal))

    val couponDiscount = order.coupon.fold(Money.zero)(c => subtotal.percentOf(c.discountPercent))
    val perkDiscount   = perk.fold(Money.zero)(p => subtotal.percentOf(p.extraDiscountPercent))

    // The two discounts are summed and then capped at the subtotal. Capping here, rather
    // than trusting that 10% + 15% never exceeds 100, is what sustains the "the total is
    // never negative" property for any combination of coupon and perk.
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

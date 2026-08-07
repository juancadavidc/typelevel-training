package com.kata.pricing.domain

import cats.data.NonEmptyList

import java.time.Instant

/** The domain model: pure data. No type here knows about DynamoDB, HTTP or `IO`.
  * `Instant` is a value, not an effect — *reading* the clock is the effect, which is why
  * `now` always arrives as a parameter instead of being called from within.
  */

/** A closed ADT: if a new tier appears tomorrow, the compiler points at every `match`
  * left incomplete. With loose strings the failure would be at runtime. */
enum Tier(val code: String):
  case Basic  extends Tier("BASIC")
  case Silver extends Tier("SILVER")
  case Gold   extends Tier("GOLD")

object Tier:
  def from(raw: String): Either[String, Tier] =
    Tier.values.find(_.code == raw.trim.toUpperCase).toRight(s"unknown tier '$raw'")

enum OrderStatus(val code: String):
  case Priced extends OrderStatus("PRICED")

final case class Customer(id: CustomerId, tier: Tier, name: Option[String], createdAt: Instant)

final case class Coupon(
    code: CouponCode,
    discountPercent: Percent,
    minOrderAmount: Money,
    usageLimit: Int,
    usageCount: Int,
    expiresAt: Instant,
    stackableWithTiers: Set[Tier]
):
  def isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
  def isExhausted: Boolean               = usageCount >= usageLimit
  def stacksWith(tier: Tier): Boolean    = stackableWithTiers.contains(tier)

/** A perk from the external partner. Optional on purpose: the service must degrade
  * without a perk when the partner fails rather than break the request (the brief
  * requires it and the WireMock tests check it). */
final case class Perk(extraDiscountPercent: Percent)

/** The request as it arrives: `sku` and `quantity` are still primitives because they
  * have not been validated yet. Crossing from here to `OrderLine` is the parse point. */
final case class RequestedItem(sku: String, quantity: Int)

final case class PriceRequest(
    customerId: CustomerId,
    items: List[RequestedItem],
    couponCode: Option[CouponCode]
)

/** A validated line: the sku exists in the catalog and the quantity is positive. */
final case class OrderLine(sku: Sku, quantity: Quantity, unitPrice: Money):
  def lineTotal: Money = unitPrice.times(quantity.value)

/** The result of validation. The existence of this type is what makes it impossible for
  * the pricing calculation to receive invalid data: it is not a convention, it is the type. */
final case class ValidOrder(
    customer: Customer,
    lines: NonEmptyList[OrderLine],
    coupon: Option[Coupon]
)

final case class PricedLine(sku: Sku, quantity: Quantity, unitPrice: Money, lineTotal: Money)

final case class PricedOrder(
    orderId: OrderId,
    customerId: CustomerId,
    status: OrderStatus,
    lines: NonEmptyList[PricedLine],
    subtotal: Money,
    discountAmount: Money,
    total: Money,
    couponApplied: Option[CouponCode],
    createdAt: Instant
)

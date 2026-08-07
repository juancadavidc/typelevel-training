package com.kata.pricing.domain

import cats.data.NonEmptyList

import java.time.Instant

/** El modelo del dominio: datos puros. Ningún tipo de aquí sabe de DynamoDB, de HTTP
  * ni de `IO`. `Instant` es un valor, no un efecto — leer el reloj sí es efecto, y por
  * eso el `now` llega siempre como parámetro en vez de llamarse desde dentro.
  */

/** ADT cerrado: si mañana aparece un tier nuevo, el compilador señala cada `match`
  * que se quedó incompleto. Con strings sueltos el fallo sería en runtime. */
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

/** Perk del partner externo. Es opcional a propósito: el servicio debe degradar sin
  * perk si el partner falla, no romper el request (lo exige el PDF y lo comprueban
  * los tests de WireMock). */
final case class Perk(extraDiscountPercent: Percent)

/** La petición tal y como llega: `sku` y `quantity` siguen siendo primitivos porque
  * todavía no se han validado. Cruzar de aquí a `OrderLine` es el punto de parseo. */
final case class RequestedItem(sku: String, quantity: Int)

final case class PriceRequest(
    customerId: CustomerId,
    items: List[RequestedItem],
    couponCode: Option[CouponCode]
)

/** Línea ya validada: el sku existe en el catálogo y la cantidad es positiva. */
final case class OrderLine(sku: Sku, quantity: Quantity, unitPrice: Money):
  def lineTotal: Money = unitPrice.times(quantity.value)

/** El resultado de la validación. Que exista este tipo es lo que hace que el cálculo
  * de precio no pueda recibir datos inválidos: no es una convención, es el tipo. */
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

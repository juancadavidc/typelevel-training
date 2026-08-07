package com.kata.pricing.domain

import cats.data.NonEmptyList

import java.time.Instant

/** Los tres campos (`code`, `field`, `message`) no son decoración: son exactamente la
  * forma del cuerpo 422 que especifica el PDF. Tenerlos en el ADT evita construir ese
  * JSON a mano en la capa HTTP, donde sería fácil que se desincronizara.
  */
enum ValidationError(val code: String, val field: String, val message: String):
  case EmptyOrder
      extends ValidationError("EMPTY_ORDER", "items", "An order must contain at least one item")

  case UnknownSku(sku: String, index: Int)
      extends ValidationError("UNKNOWN_SKU", s"items[$index].sku", s"$sku does not exist")

  case InvalidSku(reason: String, index: Int)
      extends ValidationError("INVALID_SKU", s"items[$index].sku", reason)

  case InvalidQuantity(reason: String, index: Int)
      extends ValidationError("INVALID_QUANTITY", s"items[$index].quantity", reason)

  case CouponNotFound(code0: String)
      extends ValidationError("COUPON_NOT_FOUND", "couponCode", s"Coupon $code0 does not exist")

  case CouponExpired(code0: String, expiredOn: Instant)
      extends ValidationError("COUPON_EXPIRED", "couponCode", s"Coupon $code0 expired on $expiredOn")

  case CouponExhausted(code0: String)
      extends ValidationError("COUPON_USAGE_EXCEEDED", "couponCode", s"Coupon $code0 has reached its usage limit")

  case CouponNotStackableWithTier(code0: String, tier: Tier)
      extends ValidationError("COUPON_NOT_STACKABLE", "couponCode", s"Coupon $code0 cannot be combined with tier ${tier.code}")

  case OrderBelowCouponMinimum(code0: String, minimum: Money)
      extends ValidationError("ORDER_BELOW_MINIMUM", "couponCode", s"Order total is below the ${minimum.amount} minimum required by $code0")

/** El error de la aplicación entera. `Validation` lleva una `NonEmptyList` porque el
  * 422 del PDF muestra dos errores a la vez — si llevara uno solo, ese ejemplo sería
  * irreproducible. Ver `Validation.scala` para el porqué de `Validated` frente a `Either`.
  */
enum AppError:
  case Validation(errors: NonEmptyList[ValidationError])
  case CustomerNotFound(id: CustomerId)
  case Persistence(reason: String)

object AppError:
  def validation(head: ValidationError, tail: ValidationError*): AppError =
    AppError.Validation(NonEmptyList.of(head, tail*))

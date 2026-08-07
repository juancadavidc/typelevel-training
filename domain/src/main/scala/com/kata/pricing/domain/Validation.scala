package com.kata.pricing.domain

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all.*

import java.time.Instant

/** Accumulating validation.
  *
  * THIS IS THE POINT THAT DECIDES THE 422. The brief shows a response with two
  * simultaneous errors (UNKNOWN_SKU and COUPON_EXPIRED). `Either`/`EitherT` cannot
  * produce it: their `flatMap` stops at the first `Left` by design, because the next
  * step may depend on the previous one. That is right for the overall flow, and wrong
  * for validation.
  *
  * `Validated` has no `Monad` — on purpose. It only has `Applicative`, which is why
  * `mapN` and `traverse` can run every branch and join the errors: no dependency between
  * them forces one to wait. The mental rule:
  *
  *     mapN / traverse over Validated  -> accumulate
  *     flatMap over Either / EitherT   -> short-circuit
  *
  * Both are used, each where it belongs: `Validated` in here, `EitherT` in the service
  * flow (if validation fails there is no point pricing or writing to Dynamo).
  */
object Validation:

  type Result[A] = ValidatedNel[ValidationError, A]

  def validate(
      request: PriceRequest,
      customer: Customer,
      catalog: Catalog,
      coupon: Option[Coupon],
      now: Instant
  ): Result[ValidOrder] =
    // The lines and the coupon rules that do not depend on the amount are validated in
    // parallel with `tupled`, which accumulates both sides. That is the only way to
    // reproduce the brief's 422, which shows UNKNOWN_SKU and COUPON_EXPIRED together.
    (
      validateLines(request.items, catalog),
      validateCouponRules(request.couponCode, coupon, customer.tier, now)
    ).tupled
      .andThen { (lines, validCoupon) =>
        // `andThen` is Validated's chaining (it short-circuits, like `flatMap`) and here
        // it is scoped to the ONE rule that depends on the subtotal: the coupon minimum
        // cannot be checked if the lines are invalid, because there is no trustworthy
        // subtotal. Scoping it this way is the difference between a real dependency and
        // fail-fast out of inertia.
        validateMinimumAmount(validCoupon, subtotalOf(lines))
          .map(_ => ValidOrder(customer, lines, validCoupon))
      }

  def subtotalOf(lines: NonEmptyList[OrderLine]): Money =
    lines.foldLeft(Money.zero)((accumulated, line) => accumulated.plus(line.lineTotal))

  private def validateLines(
      items: List[RequestedItem],
      catalog: Catalog
  ): Result[NonEmptyList[OrderLine]] =
    NonEmptyList.fromList(items) match
      case None => ValidationError.EmptyOrder.invalidNel
      case Some(nonEmpty) =>
        // `traverse` walks EVERY line and accumulates the errors of all of them.
        // A `foldLeft` over `Either` would have stopped at the first bad one.
        nonEmpty.zipWithIndex.traverse(validateLine(_, _, catalog))

  private def validateLine(item: RequestedItem, index: Int, catalog: Catalog): Result[OrderLine] =
    val sku: Result[Sku] =
      Sku.from(item.sku)
        .leftMap(reason => ValidationError.InvalidSku(reason, index))
        .toValidatedNel
        .andThen { parsed =>
          if catalog.contains(parsed) then parsed.validNel
          else ValidationError.UnknownSku(item.sku, index).invalidNel
        }

    val quantity: Result[Quantity] =
      Quantity.from(item.quantity)
        .leftMap(reason => ValidationError.InvalidQuantity(reason, index))
        .toValidatedNel

    // mapN evaluates both sides even when the first has already failed: an item with an
    // unknown sku AND a zero quantity produces two entries in the 422, not one.
    (sku, quantity).mapN { (validSku, validQuantity) =>
      OrderLine(validSku, validQuantity, catalog.priceOf(validSku).getOrElse(Money.zero))
    }

  /** Coupon rules that only look at the coupon itself and at the tier: expiry, exhausted
    * usage and stackability. None of them needs the order amount, so they can be
    * evaluated even when the lines are invalid — which is why they reach the 422
    * alongside the item errors.
    *
    * `requested` is the code that came in the request and `coupon` is what the
    * repository returned. Comparing them HERE, and not in the service flow, is what
    * preserves accumulation: "the coupon does not exist" is one more validation rule and
    * must add to the item errors. Had the flow short-circuited with `EitherT` on seeing
    * the repo's `None`, an order with an unknown SKU and a nonexistent coupon would
    * return a single error.
    */
  private def validateCouponRules(
      requested: Option[CouponCode],
      coupon: Option[Coupon],
      tier: Tier,
      now: Instant
  ): Result[Option[Coupon]] =
    (requested, coupon) match
      case (None, _) => None.validNel

      case (Some(code), None) => ValidationError.CouponNotFound(code.value).invalidNel

      case (Some(_), Some(candidate)) =>
        val code = candidate.code.value

        val notExpired: Result[Unit] =
          Validated.condNel(!candidate.isExpiredAt(now), (), ValidationError.CouponExpired(code, candidate.expiresAt))

        val notExhausted: Result[Unit] =
          Validated.condNel(!candidate.isExhausted, (), ValidationError.CouponExhausted(code))

        val stackable: Result[Unit] =
          Validated.condNel(candidate.stacksWith(tier), (), ValidationError.CouponNotStackableWithTier(code, tier))

        // A coupon that is expired, exhausted and non-stackable returns all three at once.
        (notExpired, notExhausted, stackable).mapN((_, _, _) => Some(candidate))

  /** The only rule that needs the subtotal, isolated so that its dependency does not
    * drag the rest of the validation into fail-fast behaviour. */
  private def validateMinimumAmount(coupon: Option[Coupon], subtotal: Money): Result[Unit] =
    coupon match
      case None => ().validNel
      case Some(candidate) =>
        Validated.condNel(
          subtotal.isAtLeast(candidate.minOrderAmount),
          (),
          ValidationError.OrderBelowCouponMinimum(candidate.code.value, candidate.minOrderAmount)
        )

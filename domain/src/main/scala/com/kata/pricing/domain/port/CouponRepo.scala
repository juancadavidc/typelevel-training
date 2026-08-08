package com.kata.pricing.domain.port

import com.kata.pricing.domain.{Coupon, CouponCode}

/** Reads the coupon rule to validate against.
  *
  * A missing coupon is `None` rather than an error: validation turns it into
  * `COUPON_NOT_FOUND` alongside whatever else is wrong with the order, which is what
  * allows the brief's 422 to carry several errors at once. An error here would
  * short-circuit and report only the first.
  */
trait CouponRepo[F[_]]:
  def find(code: CouponCode): F[Option[Coupon]]

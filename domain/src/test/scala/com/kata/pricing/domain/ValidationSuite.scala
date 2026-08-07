package com.kata.pricing.domain

import cats.data.Validated
import weaver.*

import com.kata.pricing.domain.Fixtures.*

object ValidationSuite extends SimpleIOSuite:

  private def request(items: (String, Int)*)(coupon: Option[String] = None): PriceRequest =
    PriceRequest(
      CustomerId.unsafe("cust-123"),
      items.toList.map(RequestedItem.apply),
      coupon.map(CouponCode.unsafe)
    )

  private def errorsOf(result: Validation.Result[?]): List[ValidationError] =
    result match
      case Validated.Invalid(errors) => errors.toList
      case Validated.Valid(_)        => Nil

  pureTest("a valid request produces a ValidOrder") {
    val result = Validation.validate(
      request(("SKU-001", 2), ("SKU-045", 1))(),
      customer(),
      catalog,
      coupon = None,
      now
    )
    expect(result.isValid)
  }

  /** THIS is the test that exposes having used EitherT where Validated belonged.
    *
    * The brief shows a 422 with UNKNOWN_SKU and COUPON_EXPIRED at once. With `EitherT`
    * the list would hold exactly one element and this test would fail — not through a
    * calculation error, but through picking the wrong abstraction.
    */
  pureTest("accumulates item and coupon errors in the same response") {
    val expired = coupon(code = "SUMMER10", expiresAt = now.minusSeconds(86_400))

    val result = Validation.validate(
      request(("SKU-001", 2), ("SKU-999", 1))(Some("SUMMER10")),
      customer(),
      catalog,
      Some(expired),
      now
    )

    val codes = errorsOf(result).map(_.code)
    expect.all(
      result.isInvalid,
      codes.contains("UNKNOWN_SKU"),
      codes.contains("COUPON_EXPIRED"),
      codes.size == 2
    )
  }

  pureTest("one item with an unknown sku and an invalid quantity yields two errors, not one") {
    val result = Validation.validate(request(("SKU-999", 0))(), customer(), catalog, None, now)

    val codes = errorsOf(result).map(_.code)
    expect.all(
      codes.contains("UNKNOWN_SKU"),
      codes.contains("INVALID_QUANTITY"),
      codes.size == 2
    )
  }

  pureTest("accumulates errors from every item, not just the first bad one") {
    val result =
      Validation.validate(request(("SKU-998", 1), ("SKU-001", 1), ("SKU-999", 1))(), customer(), catalog, None, now)

    val fields = errorsOf(result).map(_.field)
    expect.all(fields.contains("items[0].sku"), fields.contains("items[2].sku"), fields.size == 2)
  }

  pureTest("a coupon that is expired, exhausted and non-stackable yields all three errors at once") {
    val bad = coupon(
      expiresAt = now.minusSeconds(1),
      usageLimit = 5,
      usageCount = 5,
      tiers = Set(Tier.Gold)
    )

    val result =
      Validation.validate(request(("SKU-001", 1))(Some("SUMMER10")), customer(Tier.Basic), catalog, Some(bad), now)

    val codes = errorsOf(result).map(_.code)
    expect.all(
      codes.contains("COUPON_EXPIRED"),
      codes.contains("COUPON_USAGE_EXCEEDED"),
      codes.contains("COUPON_NOT_STACKABLE"),
      codes.size == 3
    )
  }

  pureTest("a coupon that was requested but does not exist fails with COUPON_NOT_FOUND") {
    // The repository returns None for a code that did come in the request. The flow
    // cannot decide this case before validating: short-circuiting there would put a
    // single error in the 422. See the next test.
    val result =
      Validation.validate(request(("SKU-001", 1))(Some("NOPE")), customer(), catalog, coupon = None, now)

    expect(errorsOf(result).map(_.code) == List("COUPON_NOT_FOUND"))
  }

  pureTest("a nonexistent coupon accumulates with the item errors, it does not short-circuit") {
    // The reason this check lives in Validation and not in the service flow: "the coupon
    // exists" is one more validation rule, and it has to add to the others.
    val result =
      Validation.validate(request(("SKU-999", 1))(Some("NOPE")), customer(), catalog, coupon = None, now)

    val codes = errorsOf(result).map(_.code)
    expect.all(
      codes.contains("UNKNOWN_SKU"),
      codes.contains("COUPON_NOT_FOUND"),
      codes.size == 2
    )
  }

  pureTest("a request with no coupon does not produce COUPON_NOT_FOUND") {
    val result = Validation.validate(request(("SKU-001", 1))(), customer(), catalog, coupon = None, now)
    expect(result.isValid)
  }

  pureTest("an order below the coupon minimum is rejected") {
    val demanding = coupon(minOrderAmount = BigDecimal("100.00"))

    val result =
      Validation.validate(request(("SKU-100", 1))(Some("SUMMER10")), customer(), catalog, Some(demanding), now)

    expect(errorsOf(result).map(_.code) == List("ORDER_BELOW_MINIMUM"))
  }

  pureTest("an order with no items fails with EMPTY_ORDER") {
    val result = Validation.validate(request()(), customer(), catalog, None, now)
    expect(errorsOf(result).map(_.code) == List("EMPTY_ORDER"))
  }

  pureTest("the coupon minimum is not evaluated when the lines are invalid") {
    // This pins down the top-level `andThen` in validation: with no valid lines there is
    // no subtotal, so ORDER_BELOW_MINIMUM cannot be asserted and is omitted — asserting
    // it from a partial subtotal would be a false error. The other coupon rules *are*
    // evaluated (see the accumulation test above).
    val demanding = coupon(minOrderAmount = BigDecimal("100.00"))

    val result =
      Validation.validate(request(("SKU-999", 1))(Some("SUMMER10")), customer(), catalog, Some(demanding), now)

    val codes = errorsOf(result).map(_.code)
    expect.all(codes.contains("UNKNOWN_SKU"), !codes.contains("ORDER_BELOW_MINIMUM"))
  }

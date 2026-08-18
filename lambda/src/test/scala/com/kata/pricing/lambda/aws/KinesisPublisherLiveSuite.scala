package com.kata.pricing.lambda.aws

import com.kata.pricing.domain.*
import weaver.FunSuite

import java.time.Instant

/** Pins the JSON escaping in `KinesisPublisherLive.payload` / `escapeJson`.
  *
  * There is no codec library involved (see `payload`'s docstring for why), which makes
  * the escaping entirely this file's responsibility. These tests exist to fail loudly if
  * that escaping is ever removed or narrowed.
  */
object KinesisPublisherLiveSuite extends FunSuite:

  private val orderId    = OrderId.unsafe("order-1")
  private val customerId = CustomerId.unsafe("cust-1")
  private val createdAt  = Instant.parse("2026-07-22T14:32:00Z")

  private def event(coupon: Option[CouponCode]): OrderPricedEvent =
    OrderPricedEvent(
      eventId = OrderPricedEvent.eventIdFor(orderId, createdAt),
      orderId = orderId,
      customerId = customerId,
      subtotal = Money(BigDecimal(39.98)),
      discountAmount = Money(BigDecimal(3.99)),
      total = Money(BigDecimal(35.99)),
      couponApplied = coupon,
      createdAt = createdAt
    )

  test("a coupon containing a quote is escaped, not left to break the JSON") {
    val payload = KinesisPublisherLive.payload(event(Some(CouponCode.unsafe("SUM\"MER"))))
    expect(payload.contains("\"couponApplied\":\"SUM\\\"MER\"")) and
      expect(!payload.contains("\"SUM\"MER\""))
  }

  test("a value containing a backslash is escaped") {
    val payload = KinesisPublisherLive.payload(event(Some(CouponCode.unsafe("A\\B"))))
    expect(payload.contains("\"couponApplied\":\"A\\\\B\""))
  }

  test("the no-coupon case still renders an unquoted null") {
    val payload = KinesisPublisherLive.payload(event(None))
    expect(payload.contains("\"couponApplied\":null,"))
  }

  test("escapeJson escapes quotes, backslashes and the required control characters") {
    // Built from a code point rather than a literal escape, so the source file itself
    // never has to carry a raw control character.
    val controlChar = 1.toChar.toString
    expect.eql(KinesisPublisherLive.escapeJson("a\"b"), "a\\\"b") and
      expect.eql(KinesisPublisherLive.escapeJson("a\\b"), "a\\\\b") and
      expect.eql(KinesisPublisherLive.escapeJson("a\nb"), "a\\nb") and
      expect.eql(KinesisPublisherLive.escapeJson("a\rb"), "a\\rb") and
      expect.eql(KinesisPublisherLive.escapeJson("a\tb"), "a\\tb") and
      expect.eql(KinesisPublisherLive.escapeJson(s"a${controlChar}b"), "a\\u0001b")
  }

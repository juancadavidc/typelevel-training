package com.kata.pricing.lambda

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue
import com.kata.pricing.domain.OrderPricedEvent
import weaver.SimpleIOSuite

/** `SimpleIOSuite`, and every case is a `pureTest` — decoding is a total function, so no
  * effect runtime is involved despite the suite's name.
  *
  * Not `weaver.FunSuite`: verified that it does not provide `pureTest` at all (it is
  * `BaseIOSuite with Expectations.Helpers`, and `pureTest` lives on `FSuite`). Every
  * suite in this repo extends `SimpleIOSuite` for the same reason.
  */
object StreamDecoderSuite extends SimpleIOSuite:

  pureTest("an INSERT with a full NEW_IMAGE decodes to an event") {
    val record = Fixtures.insertRecord("order-1", "2026-07-22T14:32:00Z", "seq-1")
    StreamDecoder.decode(record) match
      case Right(Some(event)) =>
        expect.eql(event.orderId.value, "order-1") and
          expect.eql(event.customerId.value, "cust-123") and
          expect.eql(event.total.amount, BigDecimal("35.99")) and
          expect.eql(event.couponApplied.map(_.value), Some("SUMMER10"))
      case other => failure(s"expected a decoded event, got $other")
  }

  /** `expect(... == ...)` rather than `expect.eql`: `expect.eql` needs a cats `Eq`, and
    * this project defines none — it compares structurally with `==` everywhere (see
    * `ValidationSuite`). Adding an `Eq` instance to the pure core solely to satisfy a
    * test would invent a convention the codebase does not use. */
  pureTest("a REMOVE is skipped, not an error") {
    expect(StreamDecoder.decode(Fixtures.removeRecord("order-1")) == Right(None))
  }

  pureTest("a MODIFY decodes: a re-priced order is still a priced order") {
    val record = Fixtures.insertRecord("order-2", "2026-07-22T14:32:00Z", "seq-2")
    record.setEventName("MODIFY")
    expect(StreamDecoder.decode(record).exists(_.isDefined))
  }

  pureTest("an order with no coupon decodes with couponApplied empty") {
    val record = Fixtures.insertRecord("order-3", "2026-07-22T14:32:00Z", "seq-3", coupon = None)
    expect(StreamDecoder.decode(record).exists(_.exists(_.couponApplied.isEmpty)))
  }

  /** A missing required attribute is a Left, never a silently-dropped record. Dropping
    * it would mean an order was priced and nobody was ever told. */
  pureTest("a NEW_IMAGE missing orderId is a decode error, not a skip") {
    val record = Fixtures.insertRecord("order-4", "2026-07-22T14:32:00Z", "seq-4")
    record.getDynamodb.getNewImage.remove("orderId")
    expect(StreamDecoder.decode(record).isLeft)
  }

  pureTest("a malformed timestamp is a decode error") {
    val record = Fixtures.insertRecord("order-5", "not-a-timestamp", "seq-5")
    expect(StreamDecoder.decode(record).isLeft)
  }

  pureTest("a malformed money amount is a decode error") {
    val record = Fixtures.insertRecord("order-6", "2026-07-22T14:32:00Z", "seq-6")
    record.getDynamodb.getNewImage.put(
      "total",
      new AttributeValue().withN("not-a-number")
    )
    expect(StreamDecoder.decode(record).isLeft)
  }

  /** The decoder is the mirror of `OrderRepoDynamo.put`. If that writer changes an
    * attribute name — or its value for a field this test does not otherwise exercise —
    * this is what catches it, because it compares the whole decoded event structurally
    * against one built directly from a `PricedOrder`, not just `eventId`.
    *
    * `eventId` alone would not do it: `eventIdFor` is a function of orderId + createdAt
    * ONLY (see `OrderPricedEvent.eventIdFor`), so a test that compared just `eventId`
    * would stay green even if `total`, `customerId`, `discountAmount`, or `couponApplied`
    * were decoded wrong — exactly the kind of break (a renamed attribute, say
    * `discountAmount`) that would fail every record in production while this test kept
    * passing. `expect(decoded == direct)` compares the full case class, catching any
    * field regressing silently.
    *
    * `==`, not `expect.eql`: `expect.eql` needs a cats `Eq`, and this project defines
    * none anywhere (see the same reasoning in `StreamProcessorSuite`). Byte-identity of
    * the whole event is the actual guarantee here, so `==` is the assertion, following
    * the project's existing convention rather than introducing a new one.
    */
  pureTest("the decoded event matches what OrderPricedEvent.from produces directly") {
    val record = Fixtures.insertRecord("order-7", "2026-07-22T14:32:00Z", "seq-7")
    val direct = OrderPricedEvent.from(
      com.kata.pricing.domain.Fixtures.pricedOrder("order-7")
    )
    StreamDecoder.decode(record) match
      case Right(Some(decoded)) => expect(decoded == direct)
      case other                => failure(s"expected a decoded event, got $other")
  }

package com.kata.pricing.service.adapter.rest

import com.kata.pricing as api
import smithy4s.json.Json
import smithy4s.time.Timestamp
import weaver.FunSuite

import java.time.Instant

/** What the contract puts on the wire, not what the generated case class holds.
  *
  * `PricingServiceImplSuite` calls `priceOrder` and asserts fields of the returned DTO. It
  * never builds a request, never touches the JSON codec, and so cannot see a serialization
  * defect — it passed while `createdAt` went out as `1787072056.690919`, because the model
  * declared no `@timestampFormat` and smithy4s fell back to the protocol default of
  * epoch-seconds. The brief pins `"2026-07-22T14:32:00Z"` in its example response and
  * `(String, ISO-8601)` in its data model.
  *
  * The gap was not a missing assertion in that suite — it was a missing *level*. Asserting
  * the model can never cover the encoding of the model, so this suite encodes through the
  * same `smithy4s-json` codec the http4s routes use and reads the bytes.
  */
object ContractWireFormatSuite extends FunSuite:

  private val createdAt = Instant.parse("2026-07-22T14:32:00Z")

  /** The brief's worked example, with no loyalty perk: subtotal 89.97, 10% off rounded
    * down to 8.99, total 80.98. */
  private val response = api.PricedOrderResponse(
    orderId = api.OrderId("ord-9f2c9b7a"),
    customerId = api.CustomerId("cust-123"),
    status = api.OrderStatus.PRICED,
    items = List(
      api.PricedItem("SKU-001", 2, api.Money(BigDecimal("19.99")), api.Money(BigDecimal("39.98"))),
      api.PricedItem("SKU-045", 1, api.Money(BigDecimal("49.99")), api.Money(BigDecimal("49.99")))
    ),
    subtotal = api.Money(BigDecimal("89.97")),
    discountAmount = api.Money(BigDecimal("8.99")),
    total = api.Money(BigDecimal("80.98")),
    createdAt = Timestamp.fromInstant(createdAt),
    couponApplied = Some(api.CouponCode("SUMMER10"))
  )

  private val body = Json.writeBlob(response).toUTF8String

  test("createdAt is an ISO-8601 string, not epoch seconds") {
    expect(body.contains("\"createdAt\":\"2026-07-22T14:32:00Z\""))
  }

  /** The regression stated as the thing that must not come back, rather than only as the
    * correct value: a future edit that drops the trait produces a number here, and a
    * contains-check on the right string would fail without saying why. */
  test("createdAt is quoted, so no numeric rendering can creep back in") {
    expect(!body.contains("\"createdAt\":1")) and
      expect(!body.contains("\"createdAt\":\"1"))
  }

  /** `bigDecimal` is the right domain choice and the wrong wire type if it serialises as a
    * string: the brief's response shows `19.99`, unquoted. Some JSON codecs quote decimals
    * to protect precision, so this is worth pinning rather than assuming. */
  test("Money renders as a bare JSON number, as the brief's response shows") {
    expect(body.contains("\"subtotal\":89.97")) and
      expect(body.contains("\"total\":80.98")) and
      expect(body.contains("\"unitPrice\":19.99"))
  }

  test("the full body matches the brief's example response") {
    val expected =
      """{"orderId":"ord-9f2c9b7a","customerId":"cust-123","status":"PRICED",""" +
        """"items":[{"sku":"SKU-001","quantity":2,"unitPrice":19.99,"lineTotal":39.98},""" +
        """{"sku":"SKU-045","quantity":1,"unitPrice":49.99,"lineTotal":49.99}],""" +
        """"subtotal":89.97,"discountAmount":8.99,"total":80.98,""" +
        """"couponApplied":"SUMMER10","createdAt":"2026-07-22T14:32:00Z"}"""
    expect.same(body, expected)
  }

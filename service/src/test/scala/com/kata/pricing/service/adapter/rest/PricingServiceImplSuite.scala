package com.kata.pricing.service.adapter.rest

import cats.effect.IO
import cats.syntax.all.*
import com.kata.pricing as api
import com.kata.pricing.domain.*
import com.kata.pricing.domain.port.{CouponRepo, CustomerRepo, IdGen, LoyaltyClient, OrderRepo}
import natchez.Trace
import weaver.SimpleIOSuite

import java.time.Instant

/** The edge, end to end: Smithy DTO in, Smithy DTO out, with the real flow in between.
  *
  * DynamoDB is absent on purpose — the algebras are four-line fakes, which is the payoff
  * of the trait-over-`F[_]` boundary. What this actually exercises is the wiring the
  * previous suites could not: the chimney transformations both ways, the `Kleisli` being
  * run with a context built from `Clock` and `Trace`, and `AppError` becoming the
  * contract's 422.
  */
object PricingServiceImplSuite extends SimpleIOSuite:

  private val now = Instant.parse("2026-07-22T14:32:00Z")

  private val catalog = Catalog.of(
    "SKU-001" -> BigDecimal("19.99"),
    "SKU-045" -> BigDecimal("49.99")
  )

  private val goldCustomer =
    Customer(CustomerId.unsafe("cust-123"), Tier.Gold, Some("Ada"), now)

  private def customers(result: Option[Customer]): CustomerRepo[IO] =
    _ => IO.pure(result)

  private def coupons(result: Option[Coupon]): CouponRepo[IO] =
    _ => IO.pure(result)

  private val noCoupons: CouponRepo[IO] = coupons(None)

  private val noPerk: LoyaltyClient[IO] = (_, _) => IO.pure(None)

  private val discardOrders: OrderRepo[IO] = _ => IO.unit

  /** Not a SAM lambda: `newOrderId` takes no parameters, so it needs a real instance. */
  private val fixedIds: IdGen[IO] = new IdGen[IO]:
    def newOrderId: IO[OrderId] = IO.pure(OrderId.unsafe("ord-9f2c9b7a"))

  /** `Trace.Implicits.noop` keeps the suite free of an entrypoint: the spans are already
    * covered by `TracingSuite`, and what is under test here is the transformation and
    * error path. */
  import natchez.Trace.Implicits.noop

  private def service(
      customerRepo: CustomerRepo[IO] = customers(Some(goldCustomer)),
      couponRepo: CouponRepo[IO] = noCoupons
  ): api.PricingService[IO] =
    PricingServiceImpl[IO](
      PricingFlow[IO](customerRepo, couponRepo, noPerk, discardOrders, fixedIds, catalog)
    )

  private val twoItems = List(
    api.OrderItemInput("SKU-001", 2),
    api.OrderItemInput("SKU-045", 1)
  )

  test("prices the brief's example and renders it as the contract's response") {
    service()
      .priceOrder(api.CustomerId("cust-123"), twoItems, None)
      .map { response =>
        expect.all(
          response.orderId.value == "ord-9f2c9b7a",
          response.customerId.value == "cust-123",
          response.status == api.OrderStatus.PRICED,
          // The brief's numbers, arriving through chimney rather than a hand-built DTO.
          response.subtotal.value == BigDecimal("89.97"),
          response.total.value == BigDecimal("89.97"),
          response.items.map(_.sku) == List("SKU-001", "SKU-045"),
          response.items.map(_.lineTotal.value) == List(BigDecimal("39.98"), BigDecimal("49.99")),
          response.couponApplied.isEmpty
        )
      }
  }

  /** Deterministic despite reading the real clock, which is the reason to assert it this
    * way: whatever the wall time is, truncation makes the sub-second field zero. Pinning a
    * fixed `Clock` instead would only prove that a fixed instant survives the flow — it
    * could not fail if the truncation were removed.
    *
    * The brief pins `"2026-07-22T14:32:00Z"`, whole seconds. `ContractWireFormatSuite`
    * asserts the rendering of a timestamp; this asserts that the timestamp the service
    * produces is one that renders that way at all.
    */
  test("createdAt is whole seconds, as the brief's example response shows") {
    service()
      .priceOrder(api.CustomerId("cust-123"), twoItems, None)
      .map { response =>
        val instant = response.createdAt.toInstant
        expect(instant.getNano == 0) and
          expect(instant.toString.endsWith("Z")) and
          expect(!instant.toString.contains("."))
      }
  }

  test("an unknown sku becomes the contract's 422, not a 500") {
    service()
      .priceOrder(api.CustomerId("cust-123"), List(api.OrderItemInput("SKU-999", 1)), None)
      .attempt
      .map {
        case Left(error: api.ValidationException) =>
          expect(error.errors.map(_.code) == List("UNKNOWN_SKU")) and
            expect(error.errors.map(_.field) == List("items[0].sku"))
        case other =>
          failure(s"expected a ValidationException, got $other")
      }
  }

  /** The reason `Validated` exists in this codebase, asserted at the edge rather than in
    * the domain: two independent failures have to survive all the way onto the wire. An
    * `EitherT`-based validation would surface only the first. */
  test("two independent failures both reach the response body") {
    val expired = Coupon(
      code = CouponCode.unsafe("SUMMER10"),
      discountPercent = Percent.unsafe(10),
      minOrderAmount = Money(BigDecimal("10.00")),
      usageLimit = 100,
      usageCount = 0,
      expiresAt = Instant.parse("2026-06-30T00:00:00Z"),
      stackableWithTiers = Set(Tier.Gold)
    )

    service(couponRepo = coupons(Some(expired)))
      .priceOrder(
        api.CustomerId("cust-123"),
        List(api.OrderItemInput("SKU-001", 1), api.OrderItemInput("SKU-999", 1)),
        Some(api.CouponCode("SUMMER10"))
      )
      .attempt
      .map {
        case Left(error: api.ValidationException) =>
          expect(error.errors.map(_.code).sorted == List("COUPON_EXPIRED", "UNKNOWN_SKU"))
        case other =>
          failure(s"expected a ValidationException, got $other")
      }
  }

  test("a missing customer is not a validation error") {
    service(customerRepo = customers(None))
      .priceOrder(api.CustomerId("nobody"), twoItems, None)
      .attempt
      .map {
        case Left(_: api.ValidationException) =>
          failure("a missing customer must not be reported as a 422")
        case Left(_)  => success
        case Right(_) => failure("expected a failure")
      }
  }

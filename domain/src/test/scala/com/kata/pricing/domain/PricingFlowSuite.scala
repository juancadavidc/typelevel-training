package com.kata.pricing.domain

import cats.Applicative
import cats.Id
import cats.data.{Chain, Writer}
import cats.syntax.all.*
import weaver.*

import com.kata.pricing.domain.Fixtures.*
import com.kata.pricing.domain.port.{CouponRepo, CustomerRepo, IdGen, LoyaltyClient, OrderRepo}

/** The whole point of DoD #2, made testable.
  *
  * The flow is instantiated at **two different `F`s and neither is `IO`**: `cats.Id` for
  * the assertions about the result, and `Writer[Chain[String], *]` for the ones about
  * what the flow *did*. There is no effect runtime here, no `unsafeRunSync`, and no
  * `var` recording calls (DoD #7) — the `Writer` log is the recorder.
  *
  * Note that `Id` covers the failure paths too. The error channel is `EitherT`'s, not
  * `F`'s, so `F` never has to be able to fail.
  */
object PricingFlowSuite extends SimpleIOSuite:

  private val traceId = TraceId.unsafe("trace-abc")
  private val orderId = OrderId.unsafe("ord-9f2c9b7a")
  private val context = RequestContext(traceId, now)

  private def request(items: (String, Int)*)(coupon: Option[String] = None): PriceRequest =
    PriceRequest(
      CustomerId.unsafe("cust-123"),
      items.toList.map(RequestedItem.apply),
      coupon.map(CouponCode.unsafe)
    )

  private def run[F[_]](flow: PricingFlow[F], of: PriceRequest): F[Either[AppError, PricedOrder]] =
    flow.price(of).run(context).value

  private def errorCodes(result: Either[AppError, PricedOrder]): List[String] =
    result match
      case Left(AppError.Validation(errors)) => errors.toList.map(_.code)
      case _                                 => Nil

  // --- Stubs. No mocking framework: an algebra is a trait, a fake is an anonymous class.

  private def customerRepo[F[_]: Applicative](found: Option[Customer]): CustomerRepo[F] =
    new CustomerRepo[F]:
      def find(id: CustomerId): F[Option[Customer]] = found.pure[F]

  private def couponRepo[F[_]: Applicative](found: Option[Coupon]): CouponRepo[F] =
    new CouponRepo[F]:
      def find(code: CouponCode): F[Option[Coupon]] = found.pure[F]

  private def idGen[F[_]: Applicative]: IdGen[F] =
    new IdGen[F]:
      def newOrderId: F[OrderId] = orderId.pure[F]

  // --- F = Id. Enough for everything the flow *returns*.

  private def pureFlow(
      customer: Option[Customer] = Some(Fixtures.customer()),
      coupon: Option[Coupon] = None,
      perk: Option[Perk] = None
  ): PricingFlow[Id] =
    new PricingFlow[Id](
      customerRepo[Id](customer),
      couponRepo[Id](coupon),
      new LoyaltyClient[Id]:
        def checkPerk(id: CustomerId, trace: TraceId): Id[Option[Perk]] = perk,
      new OrderRepo[Id]:
        def save(order: PricedOrder): Id[Unit] = (),
      idGen[Id],
      catalog
    )

  // --- F = Writer. Enough for everything the flow *does*, with no `var` anywhere.

  private type Audited[A] = Writer[Chain[String], A]

  private def auditedFlow(
      customer: Option[Customer] = Some(Fixtures.customer()),
      coupon: Option[Coupon] = None,
      perk: Option[Perk] = None
  ): PricingFlow[Audited] =
    new PricingFlow[Audited](
      customerRepo[Audited](customer),
      couponRepo[Audited](coupon),
      new LoyaltyClient[Audited]:
        def checkPerk(id: CustomerId, trace: TraceId): Audited[Option[Perk]] =
          Writer(Chain.one(s"loyalty:${trace.value}"), perk),
      new OrderRepo[Audited]:
        def save(order: PricedOrder): Audited[Unit] =
          Writer(Chain.one(s"save:${order.orderId.value}"), ()),
      idGen[Audited],
      catalog
    )

  private def auditOf[A](written: Audited[A]): List[String] = written.written.toList

  // ---------------------------------------------------------------------------------

  pureTest("prices the brief's example end to end, with F = Id and no effect runtime") {
    val result = run(
      pureFlow(coupon = Some(Fixtures.coupon(percent = 10))),
      request(("SKU-001", 2), ("SKU-045", 1))(Some("SUMMER10"))
    )

    expect.all(
      result.map(_.subtotal.amount) == Right(BigDecimal("89.97")),
      result.map(_.discountAmount.amount) == Right(BigDecimal("8.99")),
      result.map(_.total.amount) == Right(BigDecimal("80.98")),
      result.map(_.orderId.value) == Right("ord-9f2c9b7a"),
      result.map(_.createdAt) == Right(now),
      result.map(_.status) == Right(OrderStatus.Priced)
    )
  }

  pureTest("an unknown customer fails with CustomerNotFound before the partner is called") {
    // The short-circuit is EitherT's, and this is what buys it: the flow is sequential,
    // so a missing customer costs zero network calls downstream. The empty audit log is
    // the proof — with a `var` counter this assertion would need mutable state.
    val written = run(auditedFlow(customer = None), request(("SKU-001", 1))())
    val (log, result) = written.run

    expect.all(
      result == Left(AppError.CustomerNotFound(CustomerId.unsafe("cust-123"))),
      log.toList.isEmpty
    )
  }

  pureTest("an unknown SKU and a nonexistent coupon come back as both 422 errors, not one") {
    // The end-to-end version of the brief's 422. It passes only because validation
    // accumulates internally and the flow lifts the *whole* ValidatedNel across the
    // Validated -> EitherT hinge. Short-circuiting on the repo's None would yield one.
    val result = run(
      pureFlow(coupon = None),
      request(("SKU-999", 1))(Some("NOPE"))
    )

    val codes = errorCodes(result)
    expect.all(
      codes.contains("UNKNOWN_SKU"),
      codes.contains("COUPON_NOT_FOUND"),
      codes.size == 2
    )
  }

  pureTest("a partner perk stacks its extra discount on top of the coupon") {
    val result = run(
      pureFlow(coupon = Some(Fixtures.coupon(percent = 10)), perk = Some(Perk(Percent.unsafe(10)))),
      request(("SKU-001", 2), ("SKU-045", 1))(Some("SUMMER10"))
    )

    // 10% of 89.97 rounds DOWN to 8.99, twice.
    expect.all(
      result.map(_.discountAmount.amount) == Right(BigDecimal("17.98")),
      result.map(_.total.amount) == Right(BigDecimal("71.99"))
    )
  }

  pureTest("a partner that yields no perk prices the order anyway and does not fail") {
    // Degradation as the brief demands it. The algebra returning `Option` is what makes
    // "the partner timed out" and "the customer has no perk" the same case here — the
    // business outcome is identical, and the retry policy belongs to the client.
    val result = run(pureFlow(perk = None), request(("SKU-001", 2))())

    expect.all(
      result.map(_.total.amount) == Right(BigDecimal("39.98")),
      result.map(_.discountAmount.amount) == Right(BigDecimal("0.00"))
    )
  }

  pureTest("a priced order is persisted with the generated id, after the partner call") {
    val written = auditedFlow().price(request(("SKU-001", 1))()).run(context).value

    expect(auditOf(written) == List("loyalty:trace-abc", "save:ord-9f2c9b7a"))
  }

  pureTest("a failed validation persists nothing") {
    // The other half of the Validated -> EitherT hinge: once validation fails, the flow
    // is fail-fast, so no id is generated and no write happens. A 422 must never leave a
    // row behind.
    val written = auditedFlow().price(request(("SKU-999", 1))()).run(context).value
    val (log, result) = written.run

    expect.all(
      errorCodes(result) == List("UNKNOWN_SKU"),
      !log.toList.exists(_.startsWith("save:"))
    )
  }

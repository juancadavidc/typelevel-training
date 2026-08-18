package com.kata.pricing.service.adapter.rest

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.all.*
import com.kata.pricing as api
import com.kata.pricing.domain.{AppError, PricingFlow, RequestContext, TraceId}
import natchez.Trace
import Transformations.{toDomain, toResponse, toValidationException}

import java.time.temporal.ChronoUnit

/** The edge: where the polymorphic core is handed the things it cannot produce itself.
  *
  * Three jobs, and nothing else — the absence of business logic here is the point:
  *
  *   1. Build the `RequestContext`. Both fields are effects (a trace id and a clock
  *      reading) resolved here so the flow can stay a pure sequencing of `Monad`.
  *   2. Run the `Kleisli` by supplying that context, and the `EitherT` by unwrapping it.
  *   3. Translate `AppError` into the Smithy error the contract declares.
  *
  * `F` is still abstract. The concrete choice — `Kleisli[IO, Span[IO], *]` — happens in
  * `Main`, which keeps DoD rule 2 true all the way to the transport: `IO` appears once.
  */
final class PricingServiceImpl[F[_]: MonadThrow: Clock: Trace](
    flow: PricingFlow[F]
) extends api.PricingService[F]:

  def priceOrder(
      customerId: api.CustomerId,
      items: List[api.OrderItemInput],
      couponCode: Option[api.CouponCode]
  ): F[api.PricedOrderResponse] =
    Trace[F].span("price-order") {
      val request = api.PriceOrderRequest(customerId, items, couponCode).toDomain

      for
        context <- requestContext
        _       <- Trace[F].put(
                     "pricing.customer"   -> customerId.value,
                     "pricing.item_count" -> items.size
                   )
        result  <- flow.price(request).run(context).value
        response <- result match
                      case Right(order) =>
                        Trace[F].put("pricing.outcome" -> "priced").as(order.toResponse)
                      case Left(error) =>
                        Trace[F].put("pricing.outcome" -> "rejected") *> raise(error)
      yield response
    }

  /** The trace id is read from the ambient span rather than generated here.
    *
    * That is what makes the id in the partner's request headers the same one the spans
    * are filed under: correlation across the process boundary comes from natchez, not
    * from a locally invented value. The fallback only matters in tests running without
    * an entrypoint.
    *
    * The clock reading is truncated to whole seconds because the brief's example response
    * is `"2026-07-22T14:32:00Z"`, and the brief is the specification.
    *
    * Truncating here rather than at the point of rendering, because this is the *one* place
    * the timestamp enters the system: the same `Instant` reaches the response, the Orders
    * item and — through the stream processor's `eventId` derivation — the event's
    * idempotency key. Truncating at the edge keeps all three identical. Truncating in the
    * encoder would make the response disagree with the row it claims to describe, and the
    * `eventId` would be derived from a value no one can read back.
    */
  private def requestContext: F[RequestContext] =
    for
      traceId <- Trace[F].traceId
      now     <- Clock[F].realTimeInstant.map(_.truncatedTo(ChronoUnit.SECONDS))
    yield RequestContext(TraceId.unsafe(traceId.getOrElse("untraced")), now)

  /** Domain errors become Smithy errors. `ValidationException` is declared in the
    * contract with `@httpError(422)`, so raising it is what produces the brief's 422
    * body — smithy4s renders it, no hand-written JSON and no status code set by hand.
    *
    * `CustomerNotFound` has no counterpart in the Smithy model, so it stays an error of
    * `F` and the composition root maps it to a 500. Adding a case to the contract for it
    * would be a business decision the brief does not ask for.
    */
  private def raise(error: AppError): F[api.PricedOrderResponse] =
    error match
      case AppError.Validation(errors) =>
        MonadThrow[F].raiseError(errors.toValidationException)
      case AppError.CustomerNotFound(id) =>
        MonadThrow[F].raiseError(new NoSuchElementException(s"customer ${id.value} not found"))

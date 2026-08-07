package com.kata.pricing.domain

import cats.Monad
import cats.data.{EitherT, Kleisli}
import cats.syntax.all.*

import java.time.Instant

/** Per-request context. Both fields are effects resolved at the edge — a generated
  * correlation id and a clock reading — turned into plain data before the flow runs.
  */
final case class RequestContext(traceId: TraceId, receivedAt: Instant)

object PricingFlow:

  /** Fail-fast on `AppError`, over whatever `F` the caller picks. */
  type Fallible[F[_], A] = EitherT[F, AppError, A]

  /** The flow's full stack: a computation that reads a `RequestContext` and may fail.
    *
    * The aliases live here rather than as members of the class so that callers can name
    * the type without a path-dependent prefix — the composition root in phase 5 will
    * need `PricingFlow.Flow[IO, PricedOrder]` to interpret it.
    */
  type Flow[F[_], A] = Kleisli[[X] =>> Fallible[F, X], RequestContext, A]

/** Steps 1 to 6 of the brief, orchestrated without knowing what runs them.
  *
  * The algebras arrive through the constructor and the context through `Kleisli` because
  * they have different lifetimes: repos and the HTTP client are built once in the
  * composition root's `Resource`, while `traceId` and `receivedAt` are born and die with
  * each request. A single `Env[F]` holding both would erase that distinction and force
  * the whole record to be rebuilt per request.
  *
  * The constraint is `Monad` and nothing more. The three reads are independent and could
  * run concurrently, but capabilities get requested only when they are used — and going
  * sequential is what lets an unknown customer short-circuit before the partner call.
  */
final class PricingFlow[F[_]: Monad](
    customers: CustomerRepo[F],
    coupons: CouponRepo[F],
    loyalty: LoyaltyClient[F],
    orders: OrderRepo[F],
    ids: IdGen[F],
    catalog: Catalog
):

  import PricingFlow.{Fallible, Flow}

  private type Eff[A] = Fallible[F, A]

  /** The brief's steps 1 to 6, in its order.
    *
    * The partner call (step 3) runs before validation (step 4), which costs one network
    * call on invalid orders. Validating first would be cheaper, but deviating from an
    * explicitly numbered flow in the brief is the worse trade. A deliberate decision.
    */
  def price(request: PriceRequest): Flow[F, PricedOrder] =
    for
      context  <- Kleisli.ask[Eff, RequestContext]
      customer <- required(
                    customers.find(request.customerId),
                    AppError.CustomerNotFound(request.customerId)
                  )
      coupon   <- lift(request.couponCode.flatTraverse(coupons.find))
      perk     <- lift(loyalty.checkPerk(request.customerId, context.traceId))
      valid    <- fromValidated(
                    Validation.validate(request, customer, catalog, coupon, context.receivedAt)
                  )
      orderId  <- lift(ids.newOrderId)
      priced    = Pricing.price(valid, perk, orderId, context.receivedAt)
      _        <- lift(orders.save(priced))
    yield priced

  /** An effect that cannot fail, lifted into the stack. */
  private def lift[A](fa: F[A]): Flow[F, A] =
    Kleisli.liftF(EitherT.liftF(fa))

  /** A lookup whose absence is an application error, not a validation error. */
  private def required[A](fa: F[Option[A]], ifMissing: => AppError): Flow[F, A] =
    Kleisli.liftF(EitherT(fa.map(_.toRight(ifMissing))))

  /** The `Validated -> EitherT` hinge, and the reason both abstractions exist here.
    *
    * `Validation` accumulates internally because its rules are independent — that is the
    * only way to produce the brief's 422 with two errors at once. The moment the result
    * crosses into this flow the semantics have to flip to fail-fast: if the order is
    * invalid there is no point generating an id or writing to Dynamo. The whole
    * `NonEmptyList` travels across, so nothing is lost in the switch.
    */
  private def fromValidated[A](result: Validation.Result[A]): Flow[F, A] =
    Kleisli.liftF(EitherT.fromEither[F](result.toEither.leftMap(AppError.Validation.apply)))

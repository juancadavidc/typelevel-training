package com.kata.pricing.domain

/** The boundary between the pure core and everything that performs effects.
  *
  * Each trait names a capability the flow needs without saying how it is provided. The
  * DynamoDB and http4s implementations live in `service`, and the compiler enforces the
  * split: those libraries are not on `domain`'s classpath, so an implementation could
  * not be written here even by accident. That is DoD rule 1 made structural rather than
  * a matter of discipline.
  *
  * The practical payoff shows up in the tests: a fake is a four-line anonymous class, no
  * mocking framework, and the flow can be instantiated at `Id` or `Writer` — see
  * `PricingFlowSuite`.
  */

trait CustomerRepo[F[_]]:
  def find(id: CustomerId): F[Option[Customer]]

trait CouponRepo[F[_]]:
  def find(code: CouponCode): F[Option[Coupon]]

trait OrderRepo[F[_]]:
  def save(order: PricedOrder): F[Unit]

/** Generating an id is an effect, so it cannot be a pure function; and `domain` has no
  * `cats-effect` on its classpath, so it cannot ask for a ready-made capability either.
  * Declaring our own is the remaining option, and it is the same shape as the repos —
  * id generation is just one more effectful dependency.
  *
  * Contrast with the clock, which is *not* an algebra: `receivedAt` is read once at the
  * edge and travels in `RequestContext`. The two are treated differently on purpose —
  * the timestamp is ambient request metadata consumed by several steps, while the order
  * id is a generated domain value needed once, and only if the order turns out valid.
  */
trait IdGen[F[_]]:
  def newOrderId: F[OrderId]

/** The external loyalty partner.
  *
  * **This operation does not fail.** The brief requires the service to degrade sensibly
  * on a partner timeout or 5xx — no perk applied, no crash — and the business outcome of
  * all three cases (customer has no perk, timeout, 500) is identical. Collapsing them
  * into `Option` here means the flow never needs `MonadError[F, Throwable]`, which would
  * drag a transport detail into the pure core in exchange for a decision that always
  * comes out the same way.
  *
  * Timeout and retry policy is reliability over a transport, not a pricing rule, and it
  * belongs to the implementation (phase 6, where the `TestControl` test of DoD #6 lives).
  * What is lost is the distinction in the type; what is not lost is observability — the
  * implementation records the failure in its own natchez span.
  *
  * `traceId` is explicit because this is the one call that leaves the process, and the
  * correlation has to travel with it.
  */
trait LoyaltyClient[F[_]]:
  def checkPerk(id: CustomerId, traceId: TraceId): F[Option[Perk]]

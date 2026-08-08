package com.kata.pricing.domain.port

import com.kata.pricing.domain.{CustomerId, Perk, TraceId}

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
  * belongs to the adapter — see `LoyaltyClientHttp4s` and the `TestControl` suite that
  * pins its behaviour. What is lost is the distinction in the type; what is not lost is
  * observability: the adapter records the reason in its own natchez span.
  *
  * `traceId` is explicit because this is the one call that leaves the process, and the
  * correlation has to travel with it.
  */
trait LoyaltyClient[F[_]]:
  def checkPerk(id: CustomerId, traceId: TraceId): F[Option[Perk]]

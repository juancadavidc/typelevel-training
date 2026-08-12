package com.kata.pricing.domain.port

import com.kata.pricing.domain.OrderId

/** Generates the order id.
  *
  * Generating an id is an effect, so it cannot be a pure function; and `domain` has no
  * `cats-effect` on its classpath, so it cannot ask for a ready-made capability either.
  * Declaring our own port is the remaining option, and it is the same shape as the
  * repositories — id generation is just one more effectful dependency.
  *
  * Contrast with the clock, which is *not* a port: `receivedAt` is read once at the edge
  * and travels in `RequestContext`. The two are treated differently on purpose — the
  * timestamp is ambient request metadata consumed by several steps, while the order id is
  * a generated domain value needed once, and only if the order turns out valid.
  */
trait IdGen[F[_]]:
  def newOrderId: F[OrderId]

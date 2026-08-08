package com.kata.pricing.service.adapter.id

import cats.effect.Sync
import com.kata.pricing.domain.OrderId
import com.kata.pricing.domain.port.IdGen

import java.util.UUID

/** The `IdGen` port, driven by `UUID.randomUUID`.
  *
  * It sits in its own package rather than under `dynamo` because it talks to no external
  * system — the only reason it is an adapter at all is that generating an id is an
  * effect, and `domain` has no `cats-effect` on its classpath to suspend it with.
  *
  * `Sync[F]` and nothing more: this needs suspension, not concurrency.
  */
final class UuidIdGen[F[_]: Sync] extends IdGen[F]:
  def newOrderId: F[OrderId] =
    Sync[F].delay(OrderId.unsafe(s"ord-${UUID.randomUUID().toString.take(8)}"))

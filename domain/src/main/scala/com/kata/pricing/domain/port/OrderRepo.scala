package com.kata.pricing.domain.port

import com.kata.pricing.domain.PricedOrder

/** Persists the priced order.
  *
  * `F[Unit]` and not `F[Either[Error, Unit]]`: there is no persistence failure the domain
  * could name or recover from, so it stays an error of `F` and the composition root maps
  * it to a 500. This is also why `AppError` has no `Persistence` case — a case no path
  * emits is a promise the type makes and the code does not keep.
  *
  * This write is what DynamoDB Streams turns into an `OrderPriced` event. With NEW_IMAGE
  * enabled on the table the write *is* the event, which is why no outbox row appears in
  * this signature.
  */
trait OrderRepo[F[_]]:
  def save(order: PricedOrder): F[Unit]

package com.kata.pricing.domain.port

import com.kata.pricing.domain.{Customer, CustomerId}

/** Reads the customer whose tier drives the pricing rules.
  *
  * `F[Option[Customer]]` rather than `F[Either[Error, Customer]]`: an absent customer is
  * an ordinary outcome the flow turns into `AppError.CustomerNotFound`, while a broken
  * connection is a failure of `F`. Keeping the two apart in the type is what lets the
  * flow stay at `Monad` instead of needing `MonadError`.
  */
trait CustomerRepo[F[_]]:
  def find(id: CustomerId): F[Option[Customer]]

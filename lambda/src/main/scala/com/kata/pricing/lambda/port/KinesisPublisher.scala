package com.kata.pricing.lambda.port

import com.kata.pricing.domain.OrderPricedEvent

/** The one thing the processor needs from the outside world.
  *
  * No SDK type appears in this signature, which is what lets the pipeline be tested with
  * a `Ref` instead of a running Kinesis — and what would let the target be swapped for
  * SNS or EventBridge without touching the pipeline.
  */
trait KinesisPublisher[F[_]]:
  def publish(event: OrderPricedEvent): F[Unit]

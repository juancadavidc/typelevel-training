package com.kata.pricing.service.adapter.dynamo

import cats.effect.{Resource, Sync}
import com.kata.pricing.service.config.AwsConfig
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/** AWS clients as `Resource`, never opened and closed by hand — DoD rule 5.
  *
  * The reason the DoD singles this out: a `try/finally` releases on exception but not on
  * cancellation, and cancellation is exactly what happens when a request times out or the
  * server shuts down mid-flight. `Resource` covers both, and composes — the whole
  * dependency graph in `Main` is one expression that tears down in reverse order.
  */
object DynamoClient:

  def resource[F[_]: Sync](config: AwsConfig): Resource[F, DynamoDbAsyncClient] =
    Resource.fromAutoCloseable(Sync[F].delay {
      val builder = DynamoDbAsyncClient.builder().region(config.region)
      // Present only under LocalStack; in a real deployment the SDK resolves the endpoint.
      config.endpointOverride.foreach(builder.endpointOverride)
      builder.build()
    })

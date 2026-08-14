package com.kata.pricing.lambda.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import software.amazon.awssdk.regions.Region

import java.net.URI

/** Typed configuration, matching `service`'s `AppConfig`: no bare `sys.env(...)`.
  *
  * `KINESIS_STREAM_NAME` is injected by the CDK (`compute-stack.ts`), and
  * `AWS_ENDPOINT_URL` is present only under LocalStack. The defaults exist so the test
  * suite does not depend on a configured shell.
  */
final case class ProcessorConfig(
    region: Region,
    endpointOverride: Option[URI],
    streamName: String,
    concurrency: Int
)

object ProcessorConfig:

  /** Bounded publish concurrency, configurable rather than a literal buried in the
    * pipeline: the right value depends on the Kinesis shard count, which is deployment
    * configuration, not a property of the code. */
  private val concurrency: ConfigValue[Effect, Int] =
    env("PUBLISH_CONCURRENCY").default("4").as[Int]

  def load[F[_]: Async]: F[ProcessorConfig] =
    (
      env("AWS_REGION").default("us-east-1").map(Region.of),
      env("AWS_ENDPOINT_URL").option.map(_.map(URI.create)),
      env("KINESIS_STREAM_NAME").default("order-priced-events"),
      concurrency
    ).parMapN(ProcessorConfig.apply).load[F]

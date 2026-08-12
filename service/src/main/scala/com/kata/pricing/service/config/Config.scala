package com.kata.pricing.service.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import software.amazon.awssdk.regions.Region

import java.net.URI

/** Typed, validated configuration. The brief is explicit: no bare `sys.env(...)`.
  *
  * The value of ciris here is not that it reads env vars — it is that a missing or
  * malformed variable fails once, at startup, with a message naming the variable, rather
  * than surfacing as a `NoSuchElementException` on the first request that happens to
  * touch it.
  */
final case class AwsConfig(
    region: Region,
    endpointOverride: Option[URI],
    customersTable: String,
    couponsTable: String,
    ordersTable: String
)

final case class LoyaltyConfig(baseUri: String, timeoutMillis: Long)

final case class AppConfig(aws: AwsConfig, loyalty: LoyaltyConfig, port: Int)

object AppConfig:

  private val region: ConfigValue[Effect, Region] =
    env("AWS_REGION").default("us-east-1").map(Region.of)

  /** Present only when running against LocalStack; absent in a real deployment. That the
    * difference between the two environments is one optional variable — and not a
    * separate code path — is what keeps the LocalStack loop honest. */
  private val endpointOverride: ConfigValue[Effect, Option[URI]] =
    env("AWS_ENDPOINT_URL").option.map(_.map(URI.create))

  def load[F[_]: Async]: F[AppConfig] =
    (
      region,
      endpointOverride,
      env("CUSTOMERS_TABLE").default("Customers"),
      env("COUPONS_TABLE").default("Coupons"),
      env("ORDERS_TABLE").default("Orders"),
      env("LOYALTY_BASE_URI").default("http://localhost:8081"),
      env("LOYALTY_TIMEOUT_MS").default("500").as[Long],
      env("PORT").default("8080").as[Int]
    ).parMapN { (reg, endpoint, customers, coupons, orders, loyaltyUri, loyaltyTimeout, port) =>
      AppConfig(
        aws = AwsConfig(reg, endpoint, customers, coupons, orders),
        loyalty = LoyaltyConfig(loyaltyUri, loyaltyTimeout),
        port = port
      )
    }.load[F]

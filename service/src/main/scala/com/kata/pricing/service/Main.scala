package com.kata.pricing.service

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port}
import com.kata.pricing.PricingService
import com.kata.pricing.domain.{Catalog, PricingFlow}
import com.kata.pricing.service.adapter.dynamo.{CouponRepoDynamo, CustomerRepoDynamo, DynamoClient, OrderRepoDynamo}
import com.kata.pricing.service.adapter.id.UuidIdGen
import com.kata.pricing.service.adapter.loyalty.LoyaltyClientHttp4s
import com.kata.pricing.service.adapter.rest.PricingServiceImpl
import com.kata.pricing.service.adapter.tracing.Tracing
import com.kata.pricing.service.config.AppConfig
import natchez.EntryPoint
import natchez.log.Log
import org.http4s.{HttpRoutes, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import smithy4s.http4s.SimpleRestJsonBuilder
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.duration.*

import Tracing.App

/** The composition root, and the only file in the project that names `IO`.
  *
  * `grep -rn "IO" domain/src` returning nothing is the DoD's cheapest check, and it holds
  * because every layer below is polymorphic: the flow asks for `Monad`, the repos for
  * `Async` and `Trace`, the handler for `MonadThrow`, `Clock` and `Trace`. The decision
  * that they all become `Kleisli[IO, Span[IO], *]` is made here, once, in `type App`.
  *
  * The whole graph is a single `Resource` expression so that shutdown releases in reverse
  * order — the DynamoDB client outlives the server that uses it, and nothing leaks on
  * cancellation.
  */
object Main extends IOApp.Simple:

  /** The catalog is data rather than a fourth table: the brief's data model defines no
    * products table, and its example fixes these two prices. */
  private val catalog: Catalog = Catalog.of(
    "SKU-001" -> BigDecimal("19.99"),
    "SKU-045" -> BigDecimal("49.99")
  )

  def run: IO[Unit] =
    server.useForever

  private def server: Resource[IO, Unit] =
    for
      config     <- Resource.eval(AppConfig.load[IO])
      entryPoint <- Resource.eval(logEntryPoint)
      dynamo     <- DynamoClient.resource[IO](config.aws)
      // The HTTP client is a `Resource` for the same reason the AWS client is: its
      // connection pool has to be shut down, and doing that by hand is what `Resource`
      // exists to prevent. Built once here, shared by every request.
      httpClient <- EmberClientBuilder.default[IO].build
      routes     <- routesFor(config, dynamo, httpClient)
      host       <- Resource.eval(IO.fromOption(Host.fromString("0.0.0.0"))(bad("host")))
      port       <- Resource.eval(IO.fromOption(Port.fromInt(config.port))(bad("port")))
      _          <- EmberServerBuilder
                      .default[IO]
                      .withHost(host)
                      .withPort(port)
                      .withHttpApp(Tracing.traced(entryPoint)(routes).orNotFound)
                      .build
      _          <- Resource.eval(IO.println(s"pricing API listening on $host:$port"))
    yield ()

  /** Routes are built in `App`, not `IO`.
    *
    * smithy4s only requires `MonadThrow`, so the generated server works over any effect —
    * which is what lets the ambient span reach the handler without a single `mapK` in
    * business code. `Tracing.traced` lowers the result back to `IO` at the very edge.
    */
  private def routesFor(
      config: AppConfig,
      dynamo: DynamoDbAsyncClient,
      httpClient: Client[IO]
  ): Resource[IO, HttpRoutes[App]] =
    val flow = PricingFlow[App](
      customers = CustomerRepoDynamo[App](dynamo, config.aws.customersTable),
      coupons = CouponRepoDynamo[App](dynamo, config.aws.couponsTable),
      loyalty = LoyaltyClientHttp4s[App](
        // The client is built in `IO`; the flow runs in `App`. `translate` lifts it,
        // which is what lets the partner call take part in the request's trace instead
        // of being a blind spot in the span tree.
        httpClient.translate(Tracing.liftK)(Tracing.runNoSpanK),
        Uri.unsafeFromString(config.loyalty.baseUri),
        config.loyalty.timeoutMillis.millis
      ),
      orders = OrderRepoDynamo[App](dynamo, config.aws.ordersTable),
      ids = UuidIdGen[App],
      catalog = catalog
    )

    // The builder returns its `Resource` in `App` — allocation could in principle trace
    // too. It cannot here, since no span exists before the server starts, so a no-op span
    // is supplied to bring the resource down to `IO`. This is the one `mapK` in the
    // project, and it is at the edge rather than in business code.
    SimpleRestJsonBuilder
      .routes(PricingServiceImpl[App](flow): PricingService[App])
      .resource
      .mapK(Tracing.runNoSpanK)

  /** natchez's `Log` entrypoint, as the brief specifies: spans print to the console,
    * which is enough to practice composition. Swapping this one line for the Datadog
    * entrypoint is the whole production change — nothing else names a backend. */
  private def logEntryPoint: IO[EntryPoint[IO]] =
    Slf4jLogger.create[IO].map { implicit logger =>
      Log.entryPoint[IO]("pricing")
    }

  private def bad(what: String): Throwable =
    new IllegalArgumentException(s"invalid $what in configuration")

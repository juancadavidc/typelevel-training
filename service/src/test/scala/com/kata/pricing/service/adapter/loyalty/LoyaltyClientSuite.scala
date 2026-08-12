package com.kata.pricing.service.adapter.loyalty

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.kata.pricing.domain.{CustomerId, Percent, Perk, TraceId}
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import weaver.IOSuite

import scala.concurrent.duration.*

/** The partner call against a real HTTP stub, covering the three cases the brief names:
  * happy path, timeout, and 5xx — asserting that the service degrades rather than crashes
  * in each.
  *
  * WireMock rather than a fake `LoyaltyClient`: a stub of the algebra would test nothing
  * about the http4s wiring, the timeout, or the status handling, which is precisely where
  * the failure modes live.
  */
object LoyaltyClientSuite extends IOSuite:

  override type Res = (WireMockServer, org.http4s.client.Client[IO])

  override def sharedResource: Resource[IO, Res] =
    val server = Resource.make(
      IO {
        val wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        wm.start()
        wm
      }
    )(wm => IO(wm.stop()))

    (server, EmberClientBuilder.default[IO].build).tupled

  import natchez.Trace.Implicits.noop

  private val traceId = TraceId.unsafe("trace-abc")

  /** Each test gets its own customer id, and therefore its own stubbed URL.
    *
    * Weaver runs the tests of a suite concurrently against the shared `WireMockServer`,
    * so a `resetAll()` in one test wipes the stubs another is relying on — which is
    * exactly the interference this suite hit first time round. Partitioning by URL is the
    * fix that keeps the tests independent without serialising them.
    */
  private def customerFor(name: String): CustomerId = CustomerId.unsafe(s"cust-$name")

  private def clientFor(res: Res, timeout: FiniteDuration = 2.seconds) =
    LoyaltyClientHttp4s[IO](
      res._2,
      Uri.unsafeFromString(s"http://localhost:${res._1.port()}"),
      timeout
    )

  private def stub(res: Res, customer: CustomerId)(
      configure: com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder =>
        com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
  ): IO[Unit] =
    IO(res._1.stubFor(get(urlEqualTo(s"/loyalty/${customer.value}")).willReturn(configure(aResponse()))))
      .void

  test("happy path: a perk is returned and applied") { res =>
    val customer = customerFor("happy")
    stub(res, customer)(_.withStatus(200).withBody("5")) *>
      clientFor(res).checkPerk(customer, traceId).map { perk =>
        expect(perk == Some(Perk(Percent.unsafe(5))))
      }
  }

  test("a 500 degrades to no perk instead of failing the request") { res =>
    val customer = customerFor("server-error")
    stub(res, customer)(_.withStatus(500).withBody("boom")) *>
      clientFor(res).checkPerk(customer, traceId).map(perk => expect(perk.isEmpty))
  }

  test("a timeout degrades to no perk instead of hanging the request") { res =>
    val customer = customerFor("slow")
    // A 200 that arrives too late must be indistinguishable from no perk.
    stub(res, customer)(_.withStatus(200).withBody("5").withFixedDelay(3000)) *>
      clientFor(res, timeout = 300.millis)
        .checkPerk(customer, traceId)
        .map(perk => expect(perk.isEmpty))
  }

  test("a 404 is the ordinary 'no perk' answer, not a degradation") { res =>
    val customer = customerFor("no-perk")
    stub(res, customer)(_.withStatus(404)) *>
      clientFor(res).checkPerk(customer, traceId).map(perk => expect(perk.isEmpty))
  }

  test("the correlation id travels to the partner") { res =>
    val customer = customerFor("correlated")
    stub(res, customer)(_.withStatus(200).withBody("10")) *>
      clientFor(res).checkPerk(customer, traceId) *>
      IO {
        res._1.verify(
          getRequestedFor(urlEqualTo(s"/loyalty/${customer.value}"))
            .withHeader("X-Trace-Id", equalTo(traceId.value))
        )
      }.as(success)
  }

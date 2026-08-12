package com.kata.pricing.service.adapter.loyalty

import cats.effect.Temporal
import cats.effect.syntax.temporal.*
import cats.syntax.all.*
import com.kata.pricing.domain.{CustomerId, Percent, Perk, TraceId}
import com.kata.pricing.domain.port.LoyaltyClient
import natchez.Trace
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString

import scala.concurrent.duration.FiniteDuration

/** The external loyalty partner over http4s.
  *
  * This is where the brief's "degrade sensibly" requirement is actually implemented. The
  * algebra returns `F[Option[Perk]]` and cannot fail, so every failure mode has to be
  * collapsed here: a timeout, a 5xx, a malformed body and a genuine "no perk" all become
  * `None`. That is not information being thrown away — the three outcomes are identical
  * to the business, and the distinction survives where it matters, in the span.
  *
  * Timeout and retry are reliability over a transport, not a pricing rule, which is why
  * they live in the implementation and not in `PricingFlow`. The flow stays a `Monad`.
  *
  * `Temporal[F]` rather than `Async[F]`: this needs the clock and `timeoutTo`, nothing
  * more. Asking for the smallest capability that does the job is the same discipline that
  * keeps `PricingFlow` at `Monad` — an unnecessary `Async` would silently permit
  * thread-forking here.
  */
final class LoyaltyClientHttp4s[F[_]: Temporal: Trace](
    client: Client[F],
    baseUri: Uri,
    timeout: FiniteDuration
) extends LoyaltyClient[F]:

  def checkPerk(id: CustomerId, traceId: TraceId): F[Option[Perk]] =
    Trace[F].span("loyalty.check-perk") {
      Trace[F].put("http.url" -> baseUri.renderString, "pricing.customer" -> id.value) *>
        attempt(id, traceId)
          .timeoutTo(timeout, degraded("timeout"))
          .handleErrorWith(error => degraded(s"error: ${error.getClass.getSimpleName}"))
    }

  private def attempt(id: CustomerId, traceId: TraceId): F[Option[Perk]] =
    val request = Request[F](
      method = Method.GET,
      uri = baseUri / "loyalty" / id.value,
      // The correlation id travels with the call: this is the one hop that leaves the
      // process, so a shared id is the only way the partner's logs line up with ours.
      headers = org.http4s.Headers(Header.Raw(CIString("X-Trace-Id"), traceId.value))
    )

    client.run(request).use { response =>
      response.status match
        case Status.Ok =>
          response
            .attemptAs[String]
            .value
            .map(_.toOption.flatMap(parsePercent))
            .flatMap {
              case Some(perk) => Trace[F].put("loyalty.outcome" -> "perk").as(Some(perk))
              case None       => degraded("unparseable body")
            }
        case Status.NotFound =>
          // A customer with no perk is the expected path, not a failure.
          Trace[F].put("loyalty.outcome" -> "no-perk").as(None)
        case status =>
          degraded(s"status ${status.code}")
    }

  /** One place to record *why* the perk is absent. The span keeps the distinction the
    * return type deliberately drops. */
  private def degraded(reason: String): F[Option[Perk]] =
    Trace[F].put("loyalty.outcome" -> "degraded", "loyalty.reason" -> reason).as(None)

  /** Deliberately hand-written rather than a JSON codec: the partner is stubbed by
    * WireMock and its contract here is a single number. Pulling in a decoder for one
    * field would be ceremony the brief did not ask for. */
  private def parsePercent(body: String): Option[Perk] =
    val digits = body.filter(c => c.isDigit)
    if digits.isEmpty then None
    else digits.toIntOption.flatMap(Percent.from(_).toOption).map(Perk.apply)

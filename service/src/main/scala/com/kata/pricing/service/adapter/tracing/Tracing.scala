package com.kata.pricing.service.adapter.tracing

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import natchez.{EntryPoint, Kernel, Span, Trace}
import org.http4s.{HttpRoutes, Request}

/** The single place in the project where a tracing environment is injected.
  *
  * The question this answers: with `Kleisli` written into every business signature, the
  * context has to be threaded per call site, and that noise grows with every layer. The
  * fix is not to abandon `Kleisli` — it is to stop *naming* it. `Trace[F]` is a
  * capability constraint; `Kleisli[IO, Span[IO], *]` is one implementation of it, chosen
  * once, here. Business code asks for `Trace[F]` and never mentions the transformer.
  */
object Tracing:

  /** The application's effect type: `IO` plus an ambient span.
    *
    * This alias is the whole reason the rest of the codebase gets to stay abstract. It
    * appears in `Main` and in the middleware below; nowhere else.
    */
  type App[A] = Kleisli[IO, Span[IO], A]

  /** `Trace[App]` comes from natchez rather than being written by hand.
    *
    * `kleisliInstance` implements `span(name)(fa)` as a `Kleisli.local`: it opens a child
    * span and runs `fa` with that child as the environment. That is what makes nesting
    * automatic — a span opened inside another is its child because, while it runs, the
    * environment it reads *is* the parent. Same mechanism as `Kleisli`'s `flatMap`
    * handing the identical environment to every step, only with the environment replaced
    * for the sub-computation.
    */
  given Trace[App] = Trace.kleisliInstance[IO]

  /** Lifts a plain `IO` into `App`, ignoring the span. Used by implementations that need
    * to run an effect that does not itself trace. */
  def liftIO[A](io: IO[A]): App[A] = Kleisli.liftF(io)

  /** `IO ~> App` as a natural transformation. Needed because a `Request[IO]` carries a
    * streaming body in `IO`, and `mapK` requires a `FunctionK` rather than a plain
    * function — the body is polymorphic in its element type, so a monomorphic lambda
    * cannot express the lift. */
  val liftK: cats.arrow.FunctionK[IO, App] =
    new cats.arrow.FunctionK[IO, App]:
      def apply[A](fa: IO[A]): App[A] = liftIO(fa)

  /** Runs a traced computation by supplying the span — the `.run(env)` that every
    * `Kleisli` eventually needs. */
  def runWith[A](span: Span[IO])(app: App[A]): IO[A] = app.run(span)

  /** Turns routes that require an ambient span into ordinary `HttpRoutes[IO]`.
    *
    * This is the injection point, and there is exactly one regardless of how many
    * endpoints the service exposes — `HttpRoutes` is itself a `Kleisli`, so wrapping the
    * routes is the same move as wrapping a single handler.
    *
    * `continueOrElseRoot` is what makes the trace distributed: if the caller sent trace
    * headers, this request becomes a child of their span; if not, it starts a new root.
    * A caller-supplied trace id is honoured instead of being replaced, which is the point
    * of propagating a `Kernel` rather than generating an id locally.
    */
  def traced(entryPoint: EntryPoint[IO])(routes: HttpRoutes[App]): HttpRoutes[IO] =
    Kleisli { (request: Request[IO]) =>
      val kernel = Kernel(request.headers.headers.map(h => h.name -> h.value).toMap)
      val name   = s"${request.method.name} ${request.uri.path}"

      OptionT {
        entryPoint.continueOrElseRoot(name, kernel).use { span =>
          // `routes.run(request)` yields OptionT[App, Response[App]]; the span is applied
          // here and nowhere deeper.
          routes
            .run(request.mapK(liftK))
            .value
            .run(span)
            .map(_.map(_.mapK(runK(span))))
        }
      }
    }

  /** The natural transformation `App ~> IO` for a fixed span, needed to unwrap the
    * streaming body of a `Response[App]` back into `IO`. */
  private def runK(span: Span[IO]): cats.arrow.FunctionK[App, IO] =
    new cats.arrow.FunctionK[App, IO]:
      def apply[A](fa: App[A]): IO[A] = fa.run(span)

  /** `App ~> IO` for startup-time work that runs before any request exists.
    *
    * `Span.noop` is the honest choice over inventing a root span: allocating the routes
    * is not part of any trace, and a span with no parent and no request would be noise in
    * the output rather than information.
    */
  val runNoSpanK: cats.arrow.FunctionK[App, IO] = runK(Span.noop[IO])

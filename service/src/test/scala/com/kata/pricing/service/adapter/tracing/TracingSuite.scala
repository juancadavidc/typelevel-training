package com.kata.pricing.service.adapter.tracing

import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.kata.pricing.domain.{Customer, CustomerId}
import com.kata.pricing.domain.port.CustomerRepo
import natchez.{EntryPoint, Kernel, Span, Trace, TraceValue}
import weaver.SimpleIOSuite

import java.net.URI

/** Proves the claim that motivates `Trace[F]`: spans nest without any layer being told
  * who its parent is.
  *
  * The DoD asks for propagation through the chain, not a decorative span in the handler,
  * and "it compiles" does not show that. This suite records the tree an execution
  * actually produces and asserts its shape.
  */
object TracingSuite extends SimpleIOSuite:

  /** A `Span` that records its own name and parent instead of exporting anywhere.
    *
    * Writing a fake here is cheap because `Span[F]` is an interface over `F` — the same
    * property that makes the domain algebras testable without a mocking framework.
    */
  final case class Recorded(name: String, parent: Option[String], fields: List[String])

  final class FakeSpan(name: String, log: Ref[IO, Vector[Recorded]]) extends Span[IO]:

    def put(fields: (String, TraceValue)*): IO[Unit] =
      log.update(_.map {
        case r if r.name == name => r.copy(fields = r.fields ++ fields.map(_._1))
        case other               => other
      })

    def kernel: IO[Kernel] = IO.pure(Kernel(Map.empty))

    /** The heart of the mechanism: a child records *this* span as its parent, and it is
      * the only place the relationship is established. Callers never pass a parent. */
    def span(childName: String, options: Span.Options): Resource[IO, Span[IO]] =
      Resource.eval(
        log.update(_ :+ Recorded(childName, Some(name), Nil))
      ) *> Resource.pure(new FakeSpan(childName, log))

    def traceId: IO[Option[String]]  = IO.pure(Some("trace-1"))
    def spanId: IO[Option[String]]   = IO.pure(Some(name))
    def traceUri: IO[Option[URI]]    = IO.pure(None)
    def attachError(err: Throwable, fields: (String, TraceValue)*): IO[Unit] = IO.unit
    def log(event: String): IO[Unit]                                          = IO.unit
    def log(fields: (String, TraceValue)*): IO[Unit]                          = IO.unit
    def makeSpan(name: String, options: Span.Options): Resource[IO, Span[IO]] =
      span(name, options)

  def entryPoint(log: Ref[IO, Vector[Recorded]]): EntryPoint[IO] =
    new EntryPoint[IO]:
      def root(name: String, options: Span.Options): Resource[IO, Span[IO]] =
        Resource.eval(log.update(_ :+ Recorded(name, None, Nil))) *>
          Resource.pure(new FakeSpan(name, log))
      def continue(name: String, kernel: Kernel, options: Span.Options): Resource[IO, Span[IO]] =
        root(name, options)
      def continueOrElseRoot(
          name: String,
          kernel: Kernel,
          options: Span.Options
      ): Resource[IO, Span[IO]] = root(name, options)

  /** A repo instrumented exactly like `DynamoCustomerRepo`, minus the AWS client — the
    * point under test is the span, not DynamoDB. */
  final class TracedRepo[F[_]: cats.Monad: Trace] extends CustomerRepo[F]:
    def find(id: CustomerId): F[Option[Customer]] =
      Trace[F].span("dynamodb.get-customer") {
        Trace[F].put("db.table" -> "customers").as(None)
      }

  test("a span opened inside another records it as its parent, with no plumbing") {
    for
      log   <- Ref.of[IO, Vector[Recorded]](Vector.empty)
      given Trace[Tracing.App] = Tracing.given_Trace_App
      repo   = new TracedRepo[Tracing.App]
      // Two layers deep: the request span wraps a flow span, which wraps the repo span.
      program = Trace[Tracing.App].span("price-order") {
                  repo.find(CustomerId.unsafe("c-1"))
                }
      _     <- entryPoint(log).root("POST /orders/price").use(program.run)
      spans <- log.get
    yield
      val byName = spans.map(r => r.name -> r.parent).toMap
      expect.all(
        byName.get("POST /orders/price").contains(None),
        byName.get("price-order").contains(Some("POST /orders/price")),
        byName.get("dynamodb.get-customer").contains(Some("price-order")),
        spans.find(_.name == "dynamodb.get-customer").exists(_.fields.contains("db.table"))
      )
  }

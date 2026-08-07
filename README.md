# Pricing kata — Scala 3 + Typelevel

A deliberately small pricing microservice, built as a practice ground for a specific style:
Smithy-first contracts, a pure domain core, and business logic polymorphic over `F[_]` with
effects pushed to the edges.

The business scope is narrow on purpose — one endpoint that prices an order, plus a companion
Lambda that propagates the result. The interesting part is not the arithmetic; it is where each
decision is allowed to live.

Full brief: [docs/Scala - Typelevel Project.md](docs/Scala%20-%20Typelevel%C2%A0Project.md).
Phase-by-phase status and the Definition of Done: [docs/ROADMAP.md](docs/ROADMAP.md).

## What it does

`POST /orders/price` takes a customer, a list of items and an optional coupon, and returns the
priced order:

1. Look up the customer — unknown customer short-circuits the whole flow.
2. Resolve the coupon, if one was sent.
3. Ask the loyalty partner whether the customer has an extra-discount perk.
4. Validate the request, **accumulating** every error so a bad request returns all of them at
   once as a `422`.
5. Price it: line totals → subtotal → coupon and perk discounts → total.
6. Persist the order.

Two details carry most of the design weight:

- **Discounts are capped at the subtotal.** Coupon and perk stack, so 60% + 50% is reachable;
  capping the sum is what makes "the total is never negative" hold for *any* combination rather
  than for the combinations we happened to think of.
- **The partner call cannot fail.** A customer with no perk, a timeout and a 5xx all produce the
  same business outcome, so [`LoyaltyClient`](domain/src/main/scala/com/kata/pricing/domain/algebras.scala)
  returns `F[Option[Perk]]`. The flow never needs `MonadError`, and degrading gracefully is the
  only thing it *can* do.

## Structure

```
domain/   Pure core: models, validation, pricing, and the orchestration flow.
service/  Smithy model + smithy4s/http4s server, DynamoDB repos, partner client. IO lives here.
lambda/   DynamoDB Streams → fs2 → Kinesis processor.
cdk/      Infrastructure, reviewed as first-class code.   (phase 8, not created yet)
```

The boundary is compiler-enforced, not a convention: `domain`'s only dependency is `cats-core`.
Without cats-effect, http4s or the AWS SDK on its classpath, the pure core *cannot* import an
effect by accident — see [build.sbt](build.sbt#L39-L48). Everything the flow needs from the
outside world is declared as an algebra (`CustomerRepo[F]`, `LoyaltyClient[F]`, …) and
implemented in `service`.

The payoff shows up in the tests: a fake is a four-line anonymous class with no mocking
framework, and [`PricingFlowSuite`](domain/src/test/scala/com/kata/pricing/domain/PricingFlowSuite.scala)
instantiates the flow at `Id` and `Writer` to assert on *call order* — something you cannot
observe once everything is `IO`.

### The type stack

```scala
type Fallible[F[_], A] = EitherT[F, AppError, A]
type Flow[F[_], A]     = Kleisli[[X] =>> Fallible[F, X], RequestContext, A]
```

`Kleisli` carries the per-request context (trace id, clock reading) and `EitherT` carries
fail-fast failure. They are separate because algebras and request context have different
lifetimes — repos are built once in a `Resource`, `receivedAt` is born and dies with the request.

Validation is the one place that is *not* fail-fast: it uses `ValidatedNel` internally so the
422 can list several errors, then flips to `EitherT` on the way into the flow. See
[PricingFlow.scala](domain/src/main/scala/com/kata/pricing/domain/PricingFlow.scala#L83-L92) —
both abstractions exist precisely because the two halves need different semantics.

## Running it

Requires **JDK 21** and **sbt**. Scala 3.8.4 and every library version are pinned at the top of
[build.sbt](build.sbt#L5-L17).

```bash
sbt compile          # all modules; also runs smithy4s codegen for `service`
sbt test             # unit + property tests (weaver + ScalaCheck)
sbt domain/test      # a single module
```

`-source:future` is enabled in `domain` and `lambda` but **not** `service`: it turns
`implicit val` into an error and smithy4s 0.19.x codegen still emits it.

The LocalStack flow (`make up && make deploy && make test-integration`) arrives with phases 8–9.

## Status

| Phase | | |
|---|---|---|
| 1 | sbt skeleton + Smithy model + smithy4s codegen | ✅ |
| 2 | Pure domain: opaque types, `enum`, `BigDecimal` money, accumulating validation, pricing | ✅ |
| 3 | Orchestration over `F[_]` with `EitherT`/`Kleisli` + algebras | ✅ |
| 4 | chimney transformations DTO ↔ domain ↔ persistence | ⬜ |
| 5 | DynamoDB repos via `Resource`, ciris config, natchez spans, composition root | ⬜ |
| 6 | Loyalty client + WireMock + `TestControl` test | ⬜ |
| 7 | Lambda: DynamoDB Streams → fs2 → Kinesis, idempotent | ⬜ |
| 8 | CDK + LocalStack + docker-compose + Makefile | ⬜ |
| 9 | testcontainers integration tests + DoD self-review | ⬜ |

[docs/ROADMAP.md](docs/ROADMAP.md) is the source of truth, including the Definition of Done
checklist this is reviewed against.

## Repo conventions

Money is `BigDecimal`, never `Double`. IDs are `opaque type`s whose constructors return `Either`
("parse, don't validate"). ADTs are `enum`. No `var`, no shared mutable state.

Design decisions worth defending in review are written down rather than left in the commit log:
[docs/specs/](docs/specs/) for design records, [docs/notes/](docs/notes/) for concept notes.
[AGENTS.md](AGENTS.md) holds the operating instructions for AI agents working in this repo.

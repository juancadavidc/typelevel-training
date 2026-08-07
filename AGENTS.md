# AGENTS.md — Pricing kata (Scala 3 + Typelevel)

Guide for AI agents working in this repository. Read it before writing or reviewing
code. When in doubt about the stack, consult the `scala-typelevel-kata` skill.

## What this project is

A pricing API (`POST /orders/price`) defined in **Smithy** and implemented with
**smithy4s + http4s**. It validates against DynamoDB, persists the order, and a
**Lambda** triggered by **DynamoDB Streams** publishes `OrderPriced` to **Kinesis**.
It runs on **LocalStack** with infrastructure in **CDK**.

The business scope is **deliberately minimal**. Do not add endpoints, rules, or
abstractions the exercise does not ask for — what is evaluated is the FP style and
stack handling, not domain richness. Over-building loses points.

## Module layout (sbt)

```
domain/   ← PURE core. Models, validation, pricing. No effects, no AWS.
service/  ← smithy4s + http4s + DynamoDB repos + partner client. IO lives here.
lambda/   ← DynamoDB Streams → Kinesis processor, with fs2.
cdk/      ← infrastructure. Reviewed as first-class code.
```

The module split is not cosmetic: it makes the pure-core rule compiler-verifiable
(the `domain` classpath excludes cats-effect, http4s, and the AWS SDK).

## Mandatory rules (Definition of Done)

Honor them when writing code; flag them when reviewing even if not asked. Always
explain the *why*, don't just cite the rule.

1. **The pure core (`domain/`) does not import `IO`, http4s, or the AWS SDK.**
   Verifiable: `grep -rn "import cats.effect.IO\|http4s\|awssdk" domain/src`.
2. **Polymorphic logic in `F[_]`** with `EitherT`/`Kleisli`, interpreted to `IO` only
   at the composition root.
3. **Every DTO↔domain↔persistence transformation goes through chimney.** Custom
   mappings must be explicit and deliberate.
4. **AWS clients via `Resource`**, never opened/closed by hand.
5. **At least one weaver test uses `TestControl`** (cats-effect time control) over the
   partner timeout/retry. Stubbing and asserting a value once is not enough.
6. **IDs with `opaque type`** (`CustomerId`, `CouponCode`, `OrderId`, `Sku`), ADTs with
   `enum`. No `var`, no shared mutable state.
7. **`make up && make deploy && make test-integration` clean** from a fresh checkout.

## Kata traps (anticipate them)

- **Money in `BigDecimal`, never `Double`.** In Smithy it is `bigDecimal Money`.
  Percentage discounts in `Double` fail the property tests in confusing ways.
- **Accumulative validation with `ValidatedNel`, not `EitherT`.** The 422 carries
  multiple simultaneous errors; `EitherT` short-circuits on the first. Use both:
  `ValidatedNel` to gather validation errors, `EitherT` for the general fail-fast flow.
- **smithy4s first and early.** It is the biggest technical risk. Verify the codegen
  generates sources in Phase 1 before writing logic.
- **Streams deliver at-least-once.** The consumer must be idempotent.
- **`-source:future` breaks smithy4s codegen** (it emits `implicit val`). It is only
  enabled in `domain` and `lambda`, not in `service`. See `build.sbt`.

## Code conventions

- **Scala 3.8.4.** Indentation syntax, `enum`, `opaque type`, `extension`.
- **"Parse, don't validate":** opaque-type constructors return `Either`; `.unsafe(...)`
  only for data that already crossed a validated boundary or for fixtures.
- **Tests with weaver** (`weaver.framework.CatsEffect`) + ScalaCheck. Correct money
  generators (not `Double`).
- **Language:** code, comments, and docs in English; keep domain/stack terms as-is
  (`outbox`, `stream`, `opaque type`, `property test`).

## Commands

```bash
sbt compile            # compile all modules
sbt test               # unit tests (weaver)
sbt domain/test        # tests for a single module
sbt "service/run"      # start the API
# make up / make deploy / make test-integration  ← LocalStack flow (once a Makefile exists)
```

## When adding dependencies

Verify versions before asserting them — this stack moves fast. Consult
`references/versions.md` in the `scala-typelevel-kata` skill or Maven Central; do not
cite versions from memory. Current versions are centralized at the top of `build.sbt`.

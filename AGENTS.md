# AGENTS.md — Pricing kata (Scala 3 + Typelevel)

Operating instructions for AI agents in this repo. These are directives, not a README.
Follow them; when a task matches a skill below, invoke that skill.

## Skills to use

- **`scala-typelevel-kata`** — invoke it whenever the task touches Scala code, the
  Smithy model, the sbt build, weaver/ScalaCheck tests, CDK/LocalStack, or any of
  smithy4s / cats-effect / http4s / chimney / ciris / fs2 / natchez. Read its relevant
  `references/*.md` file **before** writing code in that area. It is the source of
  truth for versions, wiring, and stack patterns — prefer it over guessing.
- **`write_learning_note`** — after explaining a concept, feature, or gotcha the user
  found useful (or when they say "save this" / "guarda esto"), use it to persist a note
  to `docs/notes/<slug>.md`. Notes are written in English.

## Before you write code

1. Confirm the change is in scope. The business scope is **deliberately minimal** — do
   not add endpoints, rules, or abstractions the exercise does not ask for. If a request
   would broaden the domain, say so before implementing.
2. Identify the target module and respect its boundary (see layout below).
3. If the area is new to you, read the matching `references/*.md` from the
   `scala-typelevel-kata` skill first.
4. Do not assert library versions from memory — check `build.sbt` (versions are pinned
   at the top) or the skill's `references/versions.md`.

## Enforce the Definition of Done

Honor these when writing; flag violations when reviewing even if not asked, and always
explain the *why* rather than citing the rule.

1. `domain/` must not import `IO`, http4s, or the AWS SDK. Verify:
   `grep -rn "import cats.effect.IO\|http4s\|awssdk" domain/src`.
2. Keep logic polymorphic in `F[_]` (`EitherT`/`Kleisli`); interpret to `IO` only at
   the composition root.
3. Route every DTO↔domain↔persistence transformation through chimney; make custom
   mappings explicit.
4. Acquire AWS clients via `Resource`, never by hand.
5. Ensure at least one weaver test uses `TestControl` over the partner timeout/retry.
6. IDs are `opaque type`; ADTs are `enum`. No `var`, no shared mutable state.
7. `make up && make deploy && make test-integration` must pass from a fresh checkout.

## Watch for these traps

- Money is `BigDecimal`, never `Double` (`bigDecimal Money` in Smithy).
- Gather validation errors with `ValidatedNel` (the 422 returns several at once);
  reserve `EitherT` for the fail-fast flow.
- Verify smithy4s codegen produces sources before writing logic against it.
- Stream delivery is at-least-once → make consumers idempotent.
- `-source:future` breaks smithy4s codegen; it is enabled only in `domain` and
  `lambda`, not `service`. See `build.sbt`.

## Module boundaries

```
domain/   ← PURE core. Models, validation, pricing. No effects, no AWS.
service/  ← smithy4s + http4s + DynamoDB repos + partner client. IO lives here.
lambda/   ← DynamoDB Streams → Kinesis processor, with fs2.
cdk/      ← infrastructure, reviewed as first-class code.
```

The split is compiler-enforced: `domain`'s classpath excludes cats-effect, http4s, and
the AWS SDK, so rule 1 cannot be broken by accident. Keep it that way.

## Conventions to apply

- Scala 3.8.4: indentation syntax, `enum`, `opaque type`, `extension`.
- "Parse, don't validate": opaque-type constructors return `Either`; use `.unsafe(...)`
  only past a validated boundary or in fixtures.
- Tests: weaver (`weaver.framework.CatsEffect`) + ScalaCheck with correct money
  generators (not `Double`).
- Write code, comments, and docs in English; keep stack terms as-is (`outbox`,
  `stream`, `opaque type`, `property test`).

## Commands

```bash
sbt compile            # compile all modules
sbt test               # unit tests (weaver)
sbt domain/test        # tests for a single module
sbt "service/run"      # start the API
# make up / make deploy / make test-integration  ← LocalStack flow (once a Makefile exists)
```

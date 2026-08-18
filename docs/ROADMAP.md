# Roadmap & Definition of Done

Durable checklist for the pricing kata. Update the status column as phases land —
this file, not any chat session, is the source of truth for progress.

Spec: `docs/Scala - Typelevel Project.md`. Stack summary: `docs/relevant-stack.md`.

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | sbt multi-module skeleton + Smithy model + verify smithy4s codegen generates sources | ✅ done |
| 2 | Pure domain core: opaque types, enums, BigDecimal money, `Validated` accumulating validation, pricing | ✅ done |
| 3 | Polymorphic orchestration over `F[_]` with `EitherT`/`Kleisli` + algebras | ✅ done — [design](specs/2026-08-07-phase-3-orchestration-design.md) |
| 4 | chimney transformations DTO ↔ domain ↔ persistence | ✅ done |
| 5 | DynamoDB repos via `Resource`, ciris config, natchez spans, composition root | ✅ done |
| 6 | Loyalty partner client + WireMock (happy / timeout / 5xx) + `TestControl` test | ✅ done |
| 7 | Lambda: DynamoDB Streams → fs2 → Kinesis, idempotent | ✅ done — [design](superpowers/specs/2026-08-12-phase-7-stream-processor-design.md) |
| 8 | CDK + LocalStack + docker-compose + Makefile | ✅ done — deployed and verified end to end; see [what actually landed](#phase-8--what-actually-landed) |
| 9 | testcontainers integration tests, then DoD self-review | ⬜ |

## Definition of Done

Straight from the spec — this is the review checklist.

| # | Requirement | Status | Where |
|---|---|---|---|
| 1 | Pure core has zero imports of `IO`, http4s or the DynamoDB SDK; testable with no effect runtime | ✅ | `domain/` depends only on `cats-core`; verified by the grep below |
| 2 | Business logic polymorphic over `F[_]` via `EitherT`/`Kleisli`, interpreted to `IO` only at the composition root | ✅ half | `PricingFlow[F]` landed in phase 3; the `IO` interpretation is phase 5 |
| 3 | All DTO/domain/persistence transformations go through chimney; custom mappings are deliberate | ⬜ | phase 4 |
| 4 | Order write + event emission — **see the contradiction below** | ✅ | CDC via Streams; `StreamProcessor` + deterministic `eventId` |
| 5 | DynamoDB/Kinesis clients acquired via `Resource`, never opened/closed by hand | ✅ | `KinesisPublisherLive.resource`, phase 5 for DynamoDB |
| 6 | At least one weaver test drives cats-effect's time control (`TestControl`) over the partner timeout/retry | ⬜ | phase 6 |
| 7 | IDs use opaque types, domain ADTs use `enum`, no `var`, no shared mutable state | ✅ domain | must hold in `service`/`lambda` too |
| 8 | `make up && make deploy && make test-integration` clean from a fresh checkout | 🟡 | `up` and `deploy` verified by running them; `test-integration` has no suite yet — phase 9 |

### Verifying DoD #1

```bash
grep -rnE "^\s*import\s+(cats\.effect|org\.http4s|software\.amazon|smithy4s|com\.disneystreaming)" \
  domain/src/main && echo FAIL || echo OK
```

Anchored to `import` lines on purpose. The looser version this replaced matched *prose in
scaladoc* — phase 3's comments explain why `cats-effect` is absent, and that alone turned
it red. A check that cries wolf gets ignored, which is worse than no check.

Worth wiring into CI, but say plainly in review what it is: a **readability aid, not the
enforcement**. The enforcement is `build.sbt` — `domain`'s only dependency is `cats-core`,
so a reference to `IO` does not fail a grep, it fails to compile. That is the difference
between a rule and a convention.

### The DoD #4 contradiction — decide in phase 7

The spec contradicts itself and the choice must be defended in review:

- **Definition of Done** says: *"The priced-order write and its outbox row happen in one
  DynamoDB transactional write."*
- **Data model** says: *"There's no separate outbox table or item, and no
  `TransactWriteItems`: this is what change-data-capture buys you."*

The data-model section is the later, more specific statement and matches the described
architecture (DynamoDB Streams NEW_IMAGE as the event source). Leaning CDC — but the
answer required in review is *why the outbox pattern exists at all* (a state write and an
event emission cannot be two independent side effects) and *what CDC replaces it with*.
CDC removes the outbox table; it does **not** remove the need for idempotency in the
consumer, because Streams delivers at-least-once.

## Phase 2 — what actually landed

Done:
- Opaque types: `CustomerId`, `CouponCode`, `OrderId`, `Sku`, `Quantity`, `Percent`, `Money`,
  all with `Either`-returning smart constructors.
- `Money` = `BigDecimal` scale 2. `percentOf` rounds **DOWN**, not HALF_UP — the spec fixes
  10% of `89.97` as `8.99` (exact value `8.997`).
- `enum` ADTs: `Tier`, `OrderStatus`, `ValidationError`, `AppError`.
- Accumulating validation on `ValidatedNel`; the two `andThen` calls are genuine data
  dependencies (see `docs/notes/validated-accumulation.md`).
- Pure `Pricing.price`; `orderId` and `now` are parameters, never generated internally.
- 15 weaver tests, every one `pureTest` or property-based. No effect runtime involved.
  (Phase 3 added 3 more for `COUPON_NOT_FOUND`, which phase 2 declared but never emitted.)

Deliberately **not** in phase 2:
- The `F[_]` algebras (`CustomerRepo`, `CouponRepo`, `LoyaltyClient`) — phase 3.
- The `EitherT.fromEither` bridge from validation to the service flow — phase 3.
- Any mapping to/from the smithy4s-generated types — phase 4.

## Phase 3 — what actually landed

Full reasoning in [the design spec](specs/2026-08-07-phase-3-orchestration-design.md).

Done, all of it in `domain/` — zero lines in `service/`:
- Algebras over `F[_]`: `CustomerRepo`, `CouponRepo`, `OrderRepo`, `IdGen`, `LoyaltyClient`.
  No `Clock[F]`: the timestamp is read at the edge and rides in `RequestContext`.
- `PricingFlow[F[_]: Monad]` — steps 1–6 of the brief as
  `Kleisli[EitherT[F, AppError, *], RequestContext, *]`. Algebras by constructor, context
  by `Kleisli`, because they have different lifetimes.
- `fromValidated`, the `Validated -> EitherT` hinge: accumulate inside validation, then
  fail-fast for the rest of the flow.
- `TraceId` opaque type — in the domain only because `LoyaltyClient` has to carry it out.
- 7 weaver tests instantiating the flow at **two different `F`s, neither of them `IO`**:
  `cats.Id` for what the flow returns, `Writer[Chain[String], *]` for what it *does*
  (asserting persistence and call order with no `var`).
- Fixed a phase 2 defect it uncovered: `COUPON_NOT_FOUND` was declared but never emitted.

Deliberately **not** in phase 3:
- Any `IO`, `Resource` or composition root — phase 5.
- The DynamoDB and http4s implementations of the algebras — phases 5 and 6.
- Mapping to/from the smithy4s types — phase 4.

Carried forward: `AppError.CustomerNotFound` has no shape in the Smithy contract, so it
would surface as a 500 today. Phase 4 adds it.

## Phase 8 — what actually landed

The stacks were written in phase 8 but never deployed: no LocalStack token existed at the
time, so "synthesises cleanly" was all anyone could check. Running the loop for the first
time found three defects, and the shape of them is the point.

**The deploy was green while the system was broken.** `make up` and `cdklocal deploy` both
succeeded, all seven resources reached `CREATE_COMPLETE`, and the API priced the brief's
example correctly against real DynamoDB. Nothing published to Kinesis. Every failure was in
the Lambda, past the point any of it reports success.

1. **`ClassNotFoundException: StreamProcessorHandler`.** The hot-reload bucket mounts a
   directory as `/var/task`, and the Java runtime only reads `/var/task` and
   `/var/task/lib/*.jar`. It was pointed at `target/scala-3.8.4`, where the assembly sits
   next to `classes/` and sbt's zinc bookkeeping — on the classpath, none of it. Fixed by
   `sbt lambda/hotReloadStage`, which stages the jar as `target/hot-reload/lib/`.
2. **`Unable to load an HTTP implementation from any provider in the chain`.** The real
   find. `assemblyMergeStrategy` discarded all of `META-INF`, which deletes
   `META-INF/services/…SdkAsyncHttpService` — the `ServiceLoader` entry through which the
   AWS SDK v2 discovers its transport. The netty classes were in the jar the whole time
   (172 of them); only the index that announces them was gone. Fixed by concatenating
   `META-INF/services/*` ahead of the discard.
3. **`make deploy` never built the artifact.** It depended only on `cdk/node_modules`, so
   it deployed whatever the last build happened to leave on disk — and on a fresh checkout,
   an empty directory, which still deploys and only fails on first invocation. That is
   precisely the DoD #8 scenario. `lambda-artifact` is now a prerequisite.

Also removed: a `./lambda/target/scala-3.8.4:/tmp/lambda-artifacts` mount in
`docker-compose.yml` that appeared to feed hot-reload and did nothing. LocalStack hands the
*host* path to the Docker daemon as the Lambda container's bind mount — `get-function`
reports `Code.Location` as `file:///Users/...` — so it never reads the directory itself.
Proven by accident: the staged directory was never mounted into LocalStack and worked.

**What to say in review about #2.** 71 unit tests were green against a deployable artifact
that could not construct its Kinesis client. They could not have caught it: the suite runs
on sbt's classpath, where every dependency keeps its own `META-INF`, and the fat jar is
built by a code path no test exercises. The lesson is not "write more unit tests" — it is
that packaging is a distinct failure domain, and the only test that covers it is one that
invokes the deployed function. That is the argument for phase 9 doing more than mocking.

Verified end to end after the fixes — `POST /orders/price` → Orders table → stream →
Lambda → Kinesis, one record, `partitionKey` the `orderId`, deterministic `eventId`, and
`Money` rendered as bare JSON numbers.

## The `createdAt` wire format — the same blind spot, one level up

Also found in that smoke output and fixed on the same branch, because it turned out to be
the packaging lesson again in different clothes.

The API returned `"createdAt": 1787072056.690919`. The brief pins
`"createdAt": "2026-07-22T14:32:00Z"` in its example response and `(String, ISO-8601)` in
its data model, so this was a contract violation — but **not a smithy4s defect**. The model
declared `createdAt: Timestamp` with no `@timestampFormat`, and the codec did exactly what
it was told: fell back to the protocol's default JSON timestamp format, epoch-seconds. The
contract was underspecified, and the generated code was a faithful rendering of it.

Two details worth having ready in review:

- **Why the Kinesis event was correct all along.** It is not the same serializer.
  `KinesisPublisherLive` builds that JSON by hand, and `Instant.toString` is already
  ISO-8601. Two independent encoders, one contract, and only one of them had ever been
  checked against the brief — by eye, in a smoke test, today.
- **Why no test caught it.** `PricingServiceImplSuite` is named "renders it as the
  contract's response" and it passed throughout. It calls `priceOrder` and asserts fields
  of the returned case class; it never builds a request or touches the codec, and never
  asserted `createdAt` at all. The gap was not a missing assertion in that suite — it was a
  missing *level*. Asserting a model cannot cover the encoding of that model.

`ContractWireFormatSuite` closes it at the right level: it encodes through the same
`smithy4s-json` codec the http4s routes use and asserts the bytes, including the whole body
against the brief's example. Confirmed to fail without the trait — 3 of its 4 tests go red
and the diff reads `"createdAt":1784730720` — because a regression test that passes either
way is not one.

This is the second instance in one sitting of the same shape: the deployed artifact and the
serialized bytes are each a failure domain that model-level tests cannot reach. That, not
"more coverage", is the argument for what phase 9 has to exercise.

## The brief is the specification

Two more disagreements between that smoke output and the brief. Both were settled by the
same ruling — **the brief is the spec** — and neither was a defect in the code that computes
the answer. Worth having ready, because "the code is right and the output is still wrong"
is the kind of thing a reviewer probes.

### `discountAmount` was `13.48`, not the `8.99` the brief pins

A **fixtures** problem, and the reasoning matters more than the fix. The perk stacked on top
of the coupon (`8.99 + 4.49`, each rounded down), which is exactly what `Pricing.price` says
it does. Three facts settle whether that is wrong:

- The brief's step 3 is an *"(Optional partner check)"* and never states a numeric effect
  for the perk.
- The brief never says `cust-123` is GOLD, or that it has a perk at all.
- The brief's worked example is exactly 10% of `89.97` floored — the coupon alone.

So the example assumes no perk, and reproducing it means seeding the example customer
without one. Making perks *not* stack would be inventing a business rule to explain a
number, against the brief's explicit *"Resist adding more business rules"* — and it would be
the wrong rule, since nothing states it. `Pricing.price` is untouched.

The perk moved to a new `cust-789`, same GOLD tier and same coupon, so brief step 3 stays
demonstrable by hand and the two customers together *show* the stacking rule: `8.99` for
`cust-123`, `13.48` for `cust-789`. `cust-123` stays GOLD because SUMMER10 only stacks with
SILVER and GOLD — a BASIC tier there would reject the very coupon the example applies.

### `createdAt` had sub-second precision

Distinct from the wire-format defect above and found by fixing it: once the field rendered
as ISO-8601 it read `"2026-08-18T17:20:33.414702Z"`, where the brief's example is
`"2026-07-22T14:32:00Z"`. The clock read at the edge is now truncated to
`ChronoUnit.SECONDS`.

Truncated at the edge rather than in the encoder, because `requestContext` is the one place
the timestamp enters the system: the same `Instant` reaches the response, the Orders item
and — through the stream processor's `eventId` derivation — the event's idempotency key.
Truncating in the encoder would have made the response disagree with the row it claims to
describe, and left the `eventId` derived from a value nobody can read back. Verified on the
running stack: response, DynamoDB item and Kinesis event all carry the same
`2026-08-18T17:34:42Z`.

The test for it reads the *real* clock and asserts the nanosecond field is zero — which is
deterministic precisely because truncation is what makes it so. Pinning a fixed `Clock`
would only have proved that a fixed instant survives the flow, and could not fail if the
truncation were removed. Confirmed to go red without it.

`make smoke` now reproduces the brief's example response exactly, `orderId` and `createdAt`
aside. That is the precondition for phase 9's integration test asserting numbers at all.

## Open decisions to defend in review

- **Product catalog is a pure value, not a fourth DynamoDB table.** The spec's step 2 looks
  up only *customer tier and coupon rule* from DynamoDB, and the data model defines no
  products table. Keeping it pure makes "unknown SKU" testable with no effects. If a
  reviewer wants it persisted, `Catalog` becomes an `F[_]` algebra and the pure signature
  does not change.
- **`-source:future` is scoped to `domain` and `lambda`, not global.** smithy4s 0.19.11
  codegen still emits `implicit val`, which that flag rejects under Scala 3.8.
- **Rounding discounts DOWN** rather than HALF_UP — matches the spec's example and is the
  usual commercial convention (never give away fractions of a cent).
- ~~**Language consistency**~~ — settled in phase 3: **everything written is English**
  (code comments, test names, docs, commit messages). Phase 2's Spanish comments were
  translated in place; spoken/chat working language stays Spanish.

## Git flow

Phases 1–2 landed directly on `main` (the remote was empty, so there was no base branch
to open a pull request against). **From phase 3 onward, each phase goes on its own branch
and merges through a PR** — one reviewable unit per phase:

```
git switch -c feature/phase-3-polymorphic-orchestration
# ... work ...
gh pr create --base main
```

The remote uses the `github.com-personal` SSH alias (`~/.ssh/id_ed25519_personal`,
account `juancadavidc`). `gh` on this machine defaults to a different github.com account,
so `gh pr create` may need `gh auth switch -h github.com -u juancadavidc` first.

## Environment

- JDK 21 (`sdk env` in this directory — JDK 25 breaks sbt).
- sbt 1.12.15, pinned in `project/build.properties`. sbt 1.9.x cannot boot on JDK 21+.
- LocalStack requires an account and `LOCALSTACK_AUTH_TOKEN` since March 2026; the free
  Hobby tier is non-commercial use only. ECS/Fargate and CDK asset publishing are paid
  features — plan B is to define Fargate in CDK (still reviewed) and run the API in
  docker-compose, deploying the Lambda via the `hot-reload` bucket.

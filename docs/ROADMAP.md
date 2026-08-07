# Roadmap & Definition of Done

Durable checklist for the pricing kata. Update the status column as phases land —
this file, not any chat session, is the source of truth for progress.

Spec: `docs/Scala - Typelevel Project.md`. Stack summary: `docs/relevant-stack.md`.

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | sbt multi-module skeleton + Smithy model + verify smithy4s codegen generates sources | ✅ done |
| 2 | Pure domain core: opaque types, enums, BigDecimal money, `Validated` accumulating validation, pricing | ✅ done |
| 3 | Polymorphic orchestration over `F[_]` with `EitherT`/`Kleisli` + algebras | 🔨 in progress — [design](specs/2026-08-07-phase-3-orchestration-design.md) |
| 4 | chimney transformations DTO ↔ domain ↔ persistence | ⬜ |
| 5 | DynamoDB repos via `Resource`, ciris config, natchez spans, composition root | ⬜ |
| 6 | Loyalty partner client + WireMock (happy / timeout / 5xx) + `TestControl` test | ⬜ |
| 7 | Lambda: DynamoDB Streams → fs2 → Kinesis, idempotent | ⬜ |
| 8 | CDK + LocalStack + docker-compose + Makefile | ⬜ |
| 9 | testcontainers integration tests, then DoD self-review | ⬜ |

## Definition of Done

Straight from the spec — this is the review checklist.

| # | Requirement | Status | Where |
|---|---|---|---|
| 1 | Pure core has zero imports of `IO`, http4s or the DynamoDB SDK; testable with no effect runtime | ✅ | `domain/` depends only on `cats-core`; verified by the grep below |
| 2 | Business logic polymorphic over `F[_]` via `EitherT`/`Kleisli`, interpreted to `IO` only at the composition root | ⬜ | phase 3 + 5 |
| 3 | All DTO/domain/persistence transformations go through chimney; custom mappings are deliberate | ⬜ | phase 4 |
| 4 | Order write + event emission — **see the contradiction below** | ⬜ | phase 7 |
| 5 | DynamoDB/Kinesis clients acquired via `Resource`, never opened/closed by hand | ⬜ | phase 5 + 7 |
| 6 | At least one weaver test drives cats-effect's time control (`TestControl`) over the partner timeout/retry | ⬜ | phase 6 |
| 7 | IDs use opaque types, domain ADTs use `enum`, no `var`, no shared mutable state | ✅ domain | must hold in `service`/`lambda` too |
| 8 | `make up && make deploy && make test-integration` clean from a fresh checkout | ⬜ | phase 8 + 9 |

### Verifying DoD #1

```bash
grep -rn "cats.effect\|http4s\|awssdk\|smithy4s" domain/src/main && echo FAIL || echo OK
```

Worth wiring into CI: it is the cheapest bullet to verify and the most embarrassing to fail.

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

Deliberately **not** in phase 2:
- The `F[_]` algebras (`CustomerRepo`, `CouponRepo`, `LoyaltyClient`) — phase 3.
- The `EitherT.fromEither` bridge from validation to the service flow — phase 3.
- Any mapping to/from the smithy4s-generated types — phase 4.

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
- **Language consistency:** code comments are currently in Spanish and commit messages in
  English. Pick one before opening the PR.

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

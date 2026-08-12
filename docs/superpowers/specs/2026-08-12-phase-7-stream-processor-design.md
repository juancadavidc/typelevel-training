# Phase 7 — Stream Processor: DynamoDB Streams → fs2 → Kinesis

Design agreed on 2026-08-12. Covers DoD #4 (*order write + event emission*), DoD #5
(*clients via `Resource`*) and the fs2 requirement (*"process a batch of records as a
stream pipeline with bounded concurrency, not a manual loop with side effects"*).

## Scope

`lambda/` is currently an empty module: declared in `build.sbt`, aggregated by `root`,
with `weaverDeps` and the weaver `testFramework` already wired — and zero `.scala` files
under `src/main` or `src/test`. `sbt test` walks it and runs nothing. This phase fills it.

| File | State |
|---|---|
| `domain/.../OrderPricedEvent.scala` | new — the event and `eventIdFor` |
| `domain/src/test/.../OrderPricedEventSuite.scala` | new — determinism properties |
| `lambda/.../port/KinesisPublisher.scala` | new — the algebra |
| `lambda/.../StreamProcessor.scala` | new — the fs2 pipeline, polymorphic in `F` |
| `lambda/.../aws/KinesisPublisherLive.scala` | new — the interpreter, via `Resource` |
| `lambda/.../StreamProcessorHandler.scala` | new — the AWS entry point |
| `lambda/src/test/.../StreamProcessorSuite.scala` | new |

Out of scope: the real Kinesis interpreter's integration test (phase 9, testcontainers),
CDK wiring of the event source mapping (phase 8, already written).

## The DoD #4 contradiction, resolved

The brief contradicts itself and the choice must be defended in review:

- **Definition of Done** (line 214): *"The priced-order write and its outbox row happen in
  one DynamoDB transactional write."*
- **Data model** (line 140): *"There's no separate outbox table or item, and no
  `TransactWriteItems`: this is what change-data-capture buys you."*

**We follow the data model.** It is the later, more specific statement, and it matches the
architecture described everywhere else in the brief (Streams NEW_IMAGE as the event
source, line 87). Line 214 is a leftover from a pre-CDC draft.

What review must get, and what this phase is really about: the outbox pattern exists
because **a state write and an event emission cannot be two independent side effects** —
crash between them and you have an order nobody was told about, or an event for an order
that does not exist. The outbox makes them one atomic write, then dispatches
asynchronously. CDC replaces the outbox table by making the *table's own change log* the
event source: the write is the event. What CDC does **not** remove is the need for
idempotency in the consumer, because Streams delivers **at-least-once**.

So the brief's line 21 — *"implement and **explain** the outbox pattern"* — is satisfied by
explaining it and shipping the CDC variant the data model mandates.

## Idempotency: deterministic event identity

Streams is at-least-once: a record can be delivered twice, and a failed batch is retried
whole, reprocessing records that already published. Chosen approach:

```
eventId      = sha256(orderId.value + "|" + createdAt.toString)
partitionKey = orderId.value
```

Reprocessing the same record yields a **byte-identical event**, so a consumer deduplicates
on `eventId`. Consequences:

- **No dedup table, no extra write, no extra latency.** The alternative — a conditional
  `putItem` against a `processed-events` table — buys a stronger guarantee (never
  republish) at the cost of a table, one write per record, and tests that need DynamoDB or
  a mocked repo.
- **The processor stays pure.** Idempotency becomes a property of a total function, so it
  is provable with ScalaCheck and no effect runtime, instead of asserted against a mock.
- The dedup burden moves to the consumer, which is the correct boundary: only the consumer
  knows what "already handled" means for its own side effects.

`createdAt` (not `updatedAt`) is the timestamp, because `PricedOrder` has no `updatedAt`
and `OrderStatus` has a single case, `Priced` — an order is priced once and never
re-priced under the same `orderId`. **If a future phase adds re-pricing, `eventIdFor` must
take the new version field**, or two distinct prices for one order would collapse onto a
single `eventId`. This assumption is asserted by a test so the change cannot pass silently.

`eventIdFor` lives in `domain/`, not `lambda/`, for three reasons: it encodes a business
rule (what makes a pricing event unique), it is what the property tests target, and
`domain/`'s classpath cannot reach the AWS SDK even by accident (DoD #1).

## Architecture

| Unit | Module | Responsibility | Depends on |
|---|---|---|---|
| `OrderPricedEvent`, `eventIdFor` | `domain` | The event; deterministic identity. Pure. | `cats-core` |
| `KinesisPublisher[F]` | `lambda` | One-operation algebra: publish an event. | — |
| `StreamProcessor[F]` | `lambda` | The fs2 pipeline. Polymorphic in `F`. | `fs2`, `cats-effect` |
| `KinesisPublisherLive` | `lambda` | The AWS interpreter, acquired via `Resource`. | AWS SDK |
| `StreamProcessorHandler` | `lambda` | AWS entry point; the only `unsafeRun`. | lambda-java-core |

The handler's fully-qualified name is **not free**: `cdk/lib/compute-stack.ts` already
declares `com.kata.pricing.lambda.StreamProcessorHandler::handleRequest`. Renaming the
class without editing the CDK deploys a Lambda that cannot start.

`StreamProcessor` is polymorphic in `F[_]` so it can be tested without a real `IO`; the
`Handler` is the sole composition root that interprets to `IO` (DoD #2). The SDK client is
built once in a `Resource` held for the container's lifetime, never opened per invocation
(DoD #5).

## Data flow

```
DynamoDBEvent (batch)
  └─ records.filter(INSERT | MODIFY)         ← REMOVE is not a new price
       └─ NEW_IMAGE → decode → PricedOrder   ← a decode failure is an error, not a silent skip
            └─ eventIdFor(orderId, createdAt)
                 └─ parEvalMap(concurrency)(publisher.publish)
                      └─ compile.drain       ← first error aborts the pipeline
                           └─ StreamsEventResponse
                                empty on success; on abort, the first
                                unprocessed sequence number ⇒ Lambda retries from there
```

`partitionKey = orderId` guarantees ordering per order within Kinesis, which is the
property a price consumer actually needs.

## Error handling: fail fast

The first publish failure aborts the handler and propagates to Lambda, which retries the
whole batch. Records already published are republished **identically**, and the consumer
discards them — exactly the guarantee bought above. No silent loss, and it is the
semantics Lambda's retry/bisect behaviour expects.

Rejected: collecting failures and continuing — it wastes work when Kinesis is down, since
every record will fail anyway.

### The `reportBatchItemFailures` conflict

`cdk/lib/compute-stack.ts` **already sets `reportBatchItemFailures: true`** on the event
source, written in phase 8 before this design existed. That flag changes the contract:
Lambda now reads the handler's **return value** to decide what to retry, and a handler
returning `void`/`null` is read as *"the whole batch succeeded"*.

Combined with fail-fast, that silently loses data: the pipeline aborts on record *k*,
records *k…n* were never published, and Lambda — seeing no reported failures — advances
the iterator past all of them. With `retryAttempts: 3` this is not even a loud failure; it
is a quiet gap in the event stream.

Two ways out, and we take the first:

1. **Keep fail-fast; make the response honest.** `handleRequest` returns a
   `StreamsEventResponse`. On success: an empty failure list. On abort: the sequence
   number of the *first* unprocessed record, which tells Lambda to retry from there. The
   pipeline stays a fail-fast `compile.drain`; only the handler's reporting changes.
2. Remove the flag from the CDK and return `void`. Simpler code, but it throws away a
   correct-by-default setting the infra already has, and a full-batch retry redoes work
   that succeeded.

Option 1 costs one extra test — *an abort reports the first unprocessed sequence number,
not an empty list* — and keeps the deterministic-dedup guarantee doing its job: the
retried records republish identically and the consumer discards the duplicates.

## Testing

**`domain/` — pure, no effect runtime:**

- property: `eventIdFor` is deterministic — same input, same id, always.
- property: distinct `orderId` or distinct `createdAt` ⇒ distinct `eventId`.
- the assumption guard: two `PricedOrder`s differing only in a field *other than*
  `orderId`/`createdAt` produce the same `eventId` — documenting that identity is keyed on
  those two fields alone.

**`lambda/` — weaver + cats-effect:**

- a batch of N records publishes N events, with the expected partition keys.
- **the same record processed twice produces two events with an identical `eventId`** —
  the test that backs DoD #4 and the one to defend in review.
- `REMOVE` records are ignored; a batch of only `REMOVE`s publishes nothing.
- a publisher failure on record *k* aborts the batch and propagates the error.
- a successful batch reports an **empty** failure list.
- an aborted batch reports the **first unprocessed sequence number**, not an empty list —
  the test that stops `reportBatchItemFailures` from silently swallowing lost records.
- bounded concurrency: an instrumented publisher asserts in-flight publishes never exceed
  the configured limit.

The test `KinesisPublisher` is a `Ref[F, Vector[...]]` — no mocking framework, no AWS. The
real interpreter is exercised against LocalStack in phase 9, which is where that belongs.

## Configuration

Stream name, region and the LocalStack endpoint override load through **ciris**, matching
phase 5 — no bare `sys.env(...)`. Publish concurrency is configuration with a default, not
a literal buried in the pipeline.

# Phase 9 — Integration tests: testcontainers, LocalStack, and the seam nobody tests

Design agreed on 2026-08-18. Covers the brief's testcontainers requirement (*"testcontainers-scala
(LocalStack module) for automated integration tests (DynamoDB + Kinesis) run via the normal test
task in CI"*) and closes DoD #8, whose `make test-integration` currently matches no suite at all.

## What the brief fixes, and what it leaves open

Three things are not ours to decide. The brief states them:

- **The environment** — testcontainers, *"separate from the manual dev loop below, which uses
  docker-compose so people can poke at it interactively"*. The suite brings up its own LocalStack.
  `docker-compose.yml` already carries a comment saying exactly this, written in phase 8.
- **The scope** — *"(DynamoDB + Kinesis)"*. Not the repos alone. Reaching Kinesis pulls in the
  `StreamProcessor`, which is what makes this phase structural rather than additive.
- **The infra** — created by the suite through the AWS SDK, not by CDK. That follows from
  autonomy: a container started by testcontainers has no stacks deployed in it.

One thing the brief leaves genuinely open, decided here:

**The suite does not run under `sbt test`.** The brief says *"via the normal test task in CI"*, and
we deviate. `make test` promises *"no Docker, no LocalStack"* and that promise is worth more than
literal compliance: a `sbt test` that needs Docker breaks the fast inner loop every developer runs
dozens of times a day. In CI, `make test-integration` is one line and satisfies the intent. This is
a deliberate deviation and is listed as such below, not hidden.

## The gap this phase exists to close

`OrderRepoDynamo.put` writes with `software.amazon.awssdk.services.dynamodb.model.AttributeValue`
(SDK v2). `StreamDecoder.decode` reads
`com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue` (the v1 POJO shipped
in `aws-lambda-java-events`, which is what the Lambda runtime hands the handler). **These are
unrelated classes.** Nothing in the type system connects them.

The contract between them is a set of strings — `orderId`, `subtotal`, `discountAmount`,
`createdAt` — plus the convention that `Money` travels as `N` and `Instant` as `S`.
`StreamDecoder`'s scaladoc already claims this contract is *"enforced by a test rather than by
hope"*. It is not. `StreamDecoderSuite` hand-builds the POJOs it expects to receive, so it asserts
the decoder against itself.

**Rename `discountAmount` in `OrderRepoDynamo` today and every suite in the repo stays green while
the system is broken in production.** `service` tests never see the decoder; `lambda` tests never
see the repo. The seam is exactly where the two modules do not meet.

That is the argument for the module layout below, and the reason approach B was chosen over two
per-module suites.

## Module layout

A new `it` module, deliberately **not** aggregated by `root`:

```scala
lazy val it = project
  .in(file("it"))
  .dependsOn(service % "test->compile", lambda % "test->compile", domain % "test->test")
  .settings(
    name           := "pricing-integration",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.dimafeng" %% "testcontainers-scala-localstack" % tcVersion       % Test,
      "org.wiremock"  % "wiremock"                        % wiremockVersion % Test
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )
```

**`test->compile`, not `compile->compile`.** The module has no `src/main` — only `it/src/test`. So
the `service`↔`lambda` edge exists solely on the test classpath. This matters because it is the
review question this layout invites: *why do the producer and the consumer share a classpath when
production deploys them as two separate artifacts?* The answer is not a promise, it is the build:
nothing in `compile` scope can reach across, because `it` has no compile scope.

**`domain % "test->test"`** for the ScalaCheck generators and fixtures under `domain/src/test`.
Note `test->test` does *not* transitively bring `domain/src/main`; that arrives via `service` and
`lambda`, which both `dependsOn(domain)`. The same idiom is already in `build.sbt` on
`lambda.dependsOn(domain % "compile->compile;test->test")` — this follows the existing pattern
rather than inventing one.

**Not aggregated.** If `root` aggregated `it`, `sbt test` would start Docker. Leaving it out is
what makes the deviation above hold structurally instead of by convention.

`make test-integration` becomes `sbt it/test`. It is currently
`sbt "service/testOnly *IntegrationSuite"`, which matches nothing and **exits green** — the worst
of the three possible states, and a direct DoD #8 failure hiding as a pass.

## The path under test

```
POST /orders/price (http4s routes)
   → OrderRepoDynamo.put ──────────► Orders table (LocalStack, real DynamoDB)
                                          │ Streams NEW_IMAGE
                                          ▼
                        GetRecords via the DynamoDB Streams SDK v2
                                          │
                                 ⚠ type bridge (test-only)
                                          ▼
                        StreamProcessor.process(List[DynamodbStreamRecord])
                                          │
                                          ▼
                        KinesisPublisherLive ──► order-priced-events
                                          │
                                          ▼
                                 Kinesis GetRecords → assert
```

### The type bridge, and the rule that keeps it honest

`StreamProcessor.process` takes `List[DynamodbStreamRecord]` because in production the Lambda
runtime hands it those. The suite reads the stream with the v2 SDK, which returns
`software.amazon.awssdk.services.dynamodbstreams.model.Record`. Something has to convert.

That converter is **test code standing in for AWS's runtime**, which makes it the one genuinely
dangerous piece of this phase: write it wrong — say, spelling an attribute name correctly that the
repo spells wrong — and the suite goes green over a broken system.

**Rule: the bridge is purely structural.** It copies `eventName`, `sequenceNumber` and the
`NEW_IMAGE` map attribute by attribute, and it knows no domain field names. If the string
`"orderId"` appears anywhere in `StreamRecordBridge.scala`, it is written wrong. Its scaladoc says
so, because a rule that only exists in a design doc is not a rule.

## The suite

One `IntegrationSuite` on weaver's `IOSuite`, with a single `sharedResource`:

```
Resource: LocalStack container → AWS clients (Dynamo, Streams, Kinesis) → tables + stream → WireMock
```

Composed as one `Resource` — DoD #5 applied to test code as well as production code. A container
per test would quadruple a ~15 s startup and buy no isolation that data isolation does not already
give.

**Isolation comes from data, not containers.** Each test derives its own `orderId`/`customerId`
from the test name, so the tests share a table and a stream without interfering. No
truncate-between-tests, which is the fragile pattern this replaces.

Infra is created by the suite via the SDK: `CreateTable` with `StreamSpecification` NEW_IMAGE, and
`CreateStream`. WireMock stubs the loyalty partner, reusing the mappings already in
`local/wiremock/mappings`.

### Tests

1. **Full path.** The brief's example order enters through the API and comes out of Kinesis.
   Asserts `orderId`, the three amounts, and that `partitionKey` is the `orderId`.
2. **The attribute contract.** The `OrderPricedEvent` decoded off the stream equals, field by
   field, the one `OrderPricedEvent.from(order)` builds on the producer side. **This is the test
   that closes the gap above** — the only one in the repo that fails when the two `AttributeValue`
   worlds drift apart.
3. **Deterministic `eventId`.** Processing the same record twice yields the same `eventId`: two
   Kinesis records, one logical event. This is the demonstration that Streams' at-least-once
   delivery is handled — the thing CDC does *not* give you for free (see the phase 7 spec).
4. **Repos against real DynamoDB.** `CustomerRepo`/`CouponRepo` resolve the brief's seed data, and
   an unknown coupon yields `COUPON_NOT_FOUND`.

### Waiting, which is where suites like this rot

DynamoDB Streams and Kinesis are eventually consistent: a write is not immediately readable
downstream. This is where integration suites become flaky and then get ignored.

**No `IO.sleep` with a constant, anywhere.** Every wait is bounded polling — retry until the
expected record appears, with a time cap, and a failure message naming what was expected and what
arrived. A `sleep(2.seconds)` is fast on a laptop and flaky in CI; a 30 s bounded poll returns as
soon as the data lands and fails only when it genuinely never did.

**`TestControl` does not apply here**, and the distinction is worth having ready in review: DoD #6
is already met by `LoyaltyTimeoutSuite`, where time is logical and controllable. Here time belongs
to a real container. Substituting virtual time would mean testing nothing. Two tools, two problems,
easily mistaken for one.

## Known limits

Stated as a decision, not an oversight:

- **The suite does not exercise the fat jar.** `StreamProcessor` runs in-process off sbt's
  classpath. The phase 8 `META-INF/services` failure — where the assembled artifact could not
  construct its Kinesis client while 71 unit tests stayed green — would still slip through. Only
  `make deploy` plus an invocation covers packaging.
- **The suite does not exercise CDK.** Infra is created by SDK calls, so the stacks are validated
  by `make deploy`, not here.
- **It does not run under `sbt test`** — see the deviation above.

Together these mean `make up && make deploy` are not preconditions of `make test-integration`; they
validate the CDK path, which the brief treats as a first-class deliverable in its own right. The
DoD #8 chain still passes clean end to end, but the first two commands earn their place by covering
what the third deliberately does not.

## Files

| File | State |
|---|---|
| `build.sbt` | modified — the `it` module, unaggregated |
| `Makefile` | modified — `test-integration` → `sbt it/test` |
| `it/src/test/.../IntegrationSuite.scala` | new — the four tests |
| `it/src/test/.../StreamRecordBridge.scala` | new — the structural v2→v1 bridge |
| `it/src/test/.../LocalStackResource.scala` | new — container, clients, infra, WireMock |
| `docs/ROADMAP.md` | modified — phase 9 status and the stale DoD rows |

No changes to `domain`, `service` or `lambda`. If the suite required production code to change in
order to be testable, that would be a signal the suite is designed wrong.

## Out of scope

Deploying the Lambda and invoking it (covered by `make deploy` + `make smoke`), CI pipeline
configuration, and the final DoD self-review — which follows this suite, in the same phase, once
there is something to review against.

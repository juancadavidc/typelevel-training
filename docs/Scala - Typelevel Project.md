# - SCALA TYPELEVEL PROJECT 

## INTRODUCTION 

This document defines a small hands-on project for engineers preparing to join a client that works in a strongly typed, effect-oriented Scala style (Smithy4s-first contracts, catseffect,DynamoDB-Streams-driven, event propagation on AWS). The project is intentionally narrow in business scope: one pricing microservice plus one companion Lambda. The point is not the business logic itself, but practicing the specific libraries, patterns, and infrastructure habits (effect composition, typed transformations, the outbox pattern, CDKowned infra) that the client's codebases rely on daily. 

## OBJECTIVES 

By the end, an engineer should be able to: 

- Model a domain as pure data + pure functions, pushing all effects (DB, HTTP, clock) to the edges. 

- Define a service contract in **Smithy** first, and generate server/client code with **smithy4s** instead of hand-writing routes and DTOs. 

- Use **EitherT and Kleisli/ReaderT** to write business logic polymorphic over an effect type F[_], instead of hardcoding IO by hand everywhere. 

- Use **chimney** for the data transformations between API DTOs, domain models, and persistence models. 

- Use **fs2** to process a batch of records (Kinesis-style) as a stream pipeline with bounded concurrency, not a manual loop with side effects. 

- Implement and _explain_ the **outbox pattern** : why writing state + emitting an event can't be two independent side effects, and how a transactional write + async dispatcher solves it. 

- Write tests in **weaver** including property-based tests (ScalaCheck) and stubbed external calls (WireMock). 

- Provision infra with **CDK** and run it locally against **LocalStack** , since teams own their own infra there. 

## ENVIRONMENT SETUP 

- **JDK 21** locally (SXM runs 21/25 — 21 is the safe default to match). 

- **Scala 3.8.4** . 

- sbt project wired for smithy4s codegen (sbt-smithy4s plugin) from day one — the Smithy model is the _first_ artifact you write, not an afterthought bolted onto existing routes. 

## TECHNICAL STACK & SUGGESTIONS 

- 

- **Scala 3.8.4** , **cats** , **cats-effect 3** for the runtime. 

- **EitherT + Kleisli/ReaderT** : business logic should be polymorphic — e.g. def priceOrder[F[_]: Monad](...): EitherT[F, AppError, PricedOrder] — with concrete interpretation (IO) only at the edge. Thread config via Kleisli/ReaderT instead of passing it through every function signature. 

- **smithy4s** : the Smithy IDL model is the source of truth for the API; generate the http4s server/client from it. Treat this as the most important new skill in the kata — it's how the client's whole API surface (api-registry) works. 

- **http4s** as the transport smithy4s targets — you should barely hand-write routes. 

- **chimney** for all DTO to domain to persistence-model transformations. 

- **fs2** for the outbox dispatcher's batch-processing pipeline (Stream.evalSeq / parEvalMap with bounded concurrency to publish to Kinesis). 

- **ciris** for typed, validated config loading (table names, stream name, region, LocalStack endpoint override) — no bare sys.env(...). 

- **circe or jsoniter-scala** for JSON — jsoniter is fine (and is what smithy4s uses under the hood), pick whichever is less friction and be consistent. 

- **natchez** for tracing: wrap the DB calls and request handling in spans. Use natchez's Log entrypoint locally — in production this exports to Datadog, but for the kata a log-based backend is enough to practice span composition (Trace[F].span("priceorder"), propagating a span through the whole for-comprehension). 

##### - 

### DEVELOPMENT TOOLS 

- **sbt** : Proficiency in using sbt for building, testing, and managing Scala projects. 

- **IDE with Scala Support:** Familiarity with a Scala-friendly IDE such as IntelliJ IDEA with the Scala plugin or Visual Studio Code with Metals. 

- Docker: **Docker** and **Docker Compose** installed to run LocalStack and the service locally end to end (docker-compose up brings up the full local environment). 

- **AWS CLI, awslocal, and Node.js** — needed to run cdklocal/CDK (Node.js) and to poke at the local DynamoDB/Kinesis resources directly (awslocal) while developing. 

## PROJECT OVERVIEW 

### OVERALL SCOPE 

One microservice + one small companion Lambda. No auth, no multi-service orchestration beyond what's needed to demonstrate the outbox pattern. 

#### **Flow** : 

1. Pricing API (ECS Fargate service; contract defined in Smithy, implemented with smithy4s + http4s): POST /orders/price receives { customerId, items[], couponCode? }. 

2. Look up customer tier and coupon rule from DynamoDB. 

3. (Optional partner check) call an external "loyalty partner" HTTP endpoint to see if the customer qualifies for a partner perk, stubbed with WireMock in tests. 

4. Validate, accumulating all errors (not just the first): unknown/invalid SKUs, expired or over-used coupon, tier doesn't allow stacking, etc. 

5. Transform (via chimney) and compute the priced order. 

6. Write the priced order to DynamoDB. DynamoDB Streams (NEW_IMAGE) is enabled on this table, and that stream is the event source for the next step. 

7. Stream Processor Lambda, triggered directly by DynamoDB Streams on the Orders table, picks up each new/updated order record and publishes an OrderPriced event to a Kinesis stream (order-priced-events) 

That's the entire business surface. Resist adding more endpoints or business rules , the smallness is intentional so the FP + tailored-stack practice is what gets reviewed 

### API EXAMPLES 

#### **Example request - POST /orders/price:** 

`{ "customerId": "cust-123", "items": [ { "sku": "SKU-001", "quantity": 2 }, { "sku": "SKU-045", "quantity": 1 } ], "couponCode": "SUMMER10" }` **Example response - 200 OK:** 

```
{
  "orderId": "ord-9f2c9b7a",
  "customerId": "cust-123",
  "status": "PRICED",
```

```
  "items": [
    { "sku": "SKU-001", "quantity": 2, "unitPrice": 19.99, "lineTotal": 39.98 },
    { "sku": "SKU-045", "quantity": 1, "unitPrice": 49.99, "lineTotal": 49.99 }
  ],
  "subtotal": 89.97,
  "discountAmount": 8.99,
  "total": 80.98,
  "couponApplied": "SUMMER10",
  "createdAt": "2026-07-22T14:32:00Z"
}
```

#### **Example response - 422 Validation Error:** 

```
{
  "errors": [
    { "code": "UNKNOWN_SKU", "field": "items[1].sku", "message": "SKU-999 does not
exist" },
    { "code": "COUPON_EXPIRED", "field": "couponCode", "message": "Coupon SUMMER10
expired on 2026-06-30" }
  ]
}
```

The response body maps directly onto the Orders table's order item (see Data Model below) via chimney — that's the DTO-to-domain-to-persistence transformation this project is meant to exercise. 

### DATA MODEL: DYNAMODB SCHEMA 

This is the minimum schema, feel free to extend it (extra attributes, extra GSIs), but don't implement less than this. 

#### **Table: Orders (single-table design)** 

Partition key orderId (String). DynamoDB Streams (NEW_IMAGE) is enabled on this table — that stream is the event source the Stream Processor Lambda consumes directly. There's no separate outbox table or item, and no TransactWriteItems: this is what change-datacapture buys you. 

- **`orderId`** (String) 

- **`customerId`** (String) 

- **`status`** (String) — e.g. PRICED 

- **`items`** (List) — each entry: sku, quantity, unitPrice 

- **`subtotal, discountAmount, total`** (Number) 

- **`couponCode`** (String, optional) 

- **`createdAt, updatedAt`** (String, ISO-8601) 

#### **Table: Customers** 

- **`customerId`** (String, partition key) 

- **`tier`** (String) — e.g. BASIC, SILVER, GOLD 

- **`name`** (String, optional) 

- **`createdAt`** (String, ISO-8601) 

#### **Table: Coupons** 

- **`couponCode`** (String, partition key) 

- **`discountPercent or discountAmount`** (Number) — pick one representation and be consistent 

- **`minOrderAmount`** (Number) 

- **`usageLimit, usageCount`** (Number) 

- **`expiresAt`** (String, ISO-8601) 

- **`stackableWithTier`** (Boolean, or a list of tiers) 

Customers and Coupons are deliberately left as plain, independent lookup tables rather than folded into the single-table model. 

### TESTING REQUIREMENTS 

- weaver is the preferred test framework for this exercise (don't reach for munit/specs2 out of habit). 

- ScalaCheck properties for the discount/validation logic (e.g. final price is never negative, never exceeds pre-discount total). 

- WireMock to stub the external loyalty-partner HTTP call — cover the happy path, a timeout, and a 5xx, and assert the service degrades sensibly (no perk applied, not a crash) in each case. 

- testcontainers-scala (LocalStack module) for automated integration tests (DynamoDB + Kinesis) run via the normal test task in CI — separate from the manual dev loop below, which uses docker-compose so people can poke at it interactively. 

### AWS CSK + LOCALSTACK REQUIREMENT 

- Define infra with **CDK** : DynamoDB tables (Customers, Coupons; with DynamoDB Streams enabled on Orders), ECS Fargate service for the Pricing API, a Lambda subscribed directly to the Orders table's stream (the Stream Processor), and a 

Kinesis stream (order-priced-events) as the publish target. This mix (Lambda + DynamoDB Streams + Kinesis) is deliberately the client's most common pattern for propagating state changes as events. 

- Everything must run against **LocalStack** (cdklocal deploy, or cdk deploy pointed at the LocalStack endpoint) — full loop (deploy infra, run service, hit endpoint, see the outbox dispatch, tear down) with zero real AWS account involvement. 

- Deliverable includes a docker-compose.yml (LocalStack, for the interactive dev loop) + a short Makefile: make up, make deploy, make test-integration, make down. 

- Since teams at the client own their own infra end to end, treat the CDK app as a first-class part of the deliverable, not an afterthought — mentors should review the CDK code too, not just Scala. 

## DEFINITION OF DONE 

Before opening a PR, check your own work against this list, it's the same list that'll get you a fast, clean review: 

- The pure core (models, validation, pricing) has zero imports of IO, http4s, or the DynamoDB SDK, and is testable with no effect runtime involved. 

- Business logic is written polymorphically over F[_] using EitherT/Kleisli, and interpreted to IO only at the composition root. IO hasn't leaked into the "pure" layer 

- All DTO/domain/persistence transformations go through chimney. Where a transformer needed a custom mapping (renamed or derived fields), that's deliberate, not a silently hand-written copy. 

- The priced-order write and its outbox row happen in one DynamoDB transactional write. The dispatcher is safe to run twice on the same pending row. 

- DynamoDB/Kinesis clients are acquired via Resource, never manually opened or closed. 

- At least one weaver test exercises IO's time control directly (timeout/retry around the partner call), not just a mocked return value asserted once. 

- IDs use opaque types (CustomerId, CouponCode) instead of raw String; domain ADTs use enums. No var, no mutable shared state. 

- make up && make deploy && make test-integration passes clean against LocalStack from a fresh checkout. 


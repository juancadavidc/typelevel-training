# Phase 3 — Polymorphic orchestration over `F[_]`

Design agreed on 2026-08-07. Covers DoD #2 (*"business logic polymorphic over `F[_]` via
`EitherT`/`Kleisli`, interpreted to `IO` only at the composition root"*).

Branch: `feature/phase-3-polymorphic-orchestration`.

## Scope

**Everything lands in `domain/`. Zero lines in `service/`.** The http4s/smithy4s wiring
and the composition root are phase 5; what phase 3 delivers is the orchestration *that
does not yet know who runs it*. Desirable side effect: `sbt domain/test` still runs with
no effect runtime.

| File | State |
|---|---|
| `domain/.../algebras.scala` | new |
| `domain/.../PricingFlow.scala` | new |
| `domain/.../ids.scala` | add `TraceId` |
| `domain/src/test/.../PricingFlowSuite.scala` | new |
| `domain/.../Validation.scala` | already fixed in `c2865e3` (see below) |

Out of scope: DynamoDB implementations (phase 5), the partner HTTP client (phase 6),
chimney mappings (phase 4), `Resource` and `IOApp` (phase 5).

## Chosen architecture

`PricingFlow[F]` is a final class that **takes its algebras through the constructor** and
the request context **through `Kleisli`**. Errors travel on `EitherT`.

```scala
final class PricingFlow[F[_]: Monad](
    customers: CustomerRepo[F], coupons: CouponRepo[F], loyalty: LoyaltyClient[F],
    orders: OrderRepo[F], ids: IdGen[F], catalog: Catalog):

  private type Fallible[A] = EitherT[F, AppError, A]
  type Flow[A] = Kleisli[Fallible, RequestContext, A]

  def price(request: PriceRequest): Flow[PricedOrder]
```

**Why two injection mechanisms instead of one.** The algebras and the context have
different lifetimes: repos and the HTTP client are built **once** in the composition
root's `Resource`, while `traceId` and `receivedAt` are born and die with each request.
The constructor models the former, the `Reader` the latter. Putting both into a single
`Env[F]` — the full "ReaderT pattern" — erases that distinction, forces the whole record
to be rebuilt per request, and degrades compiler error messages with an `Env` recursive
in `F`.

Alternatives rejected, and why:

- **`Env[F]` holding everything (full ReaderT)** — one mechanism, but it conflates
  lifetimes and complicates phase 5.
- **Algebras as context bounds** (`[F[_]: Monad: CustomerRepo: ...]`) — a more
  self-describing signature, but worse implicit-resolution errors and more fragile
  wiring. Not a different architecture: it is the same one with another way of passing
  dependencies, and it can be adopted later without touching the flow's body.
- **Orchestrating in `service` with plain `IO`** — the simplest option, but `IO` would
  enter the business logic and DoD #2 would rest on discipline rather than on the
  classpath.

## The algebras

```scala
trait CustomerRepo[F[_]]:  def find(id: CustomerId): F[Option[Customer]]
trait CouponRepo[F[_]]:    def find(code: CouponCode): F[Option[Coupon]]
trait OrderRepo[F[_]]:     def save(order: PricedOrder): F[Unit]
trait IdGen[F[_]]:         def newOrderId: F[OrderId]
trait LoyaltyClient[F[_]]: def checkPerk(id: CustomerId, traceId: TraceId): F[Option[Perk]]
```

**`IdGen[F]` instead of `Clock[F]`.** `domain` only has `cats-core` on its classpath —
that *is* DoD rule 1 made structural — so `cats.effect.Clock` does not even compile here.
Reading the clock is an effect and somebody has to run it: either it is declared as a
capability of our own, or it is read at the edge and travels as data. We chose the
latter for the clock (`receivedAt` inside `RequestContext`) and the former for the id
(`IdGen[F].newOrderId`), because they play different roles: `receivedAt` is **ambient
request metadata** consumed by several steps, while the `orderId` is a **generated domain
value**, needed once and only if the order turns out valid.

**`checkPerk` returns `F[Option[Perk]]` and its contract says it does not fail.** The
spec requires that on timeout and on 5xx *"the service degrades sensibly (no perk
applied, not a crash)"*. The business outcome of all three cases — customer has no perk,
timeout, 500 — is identical: price without the extra discount. If the algebra returned
`F[Either[PartnerDown, Perk]]`, the flow would need `MonadError[F, Throwable]` to
recover, dragging a transport detail into the pure core, in exchange for a decision that
always yields the same result. The type states the guarantee; timeout and retry policy is
reliability over a transport, and the DoD puts it where it belongs: test #6 with
`TestControl` exercises the **client**, not the flow. Cost to defend: the distinction is
lost in the type, but not in observability — the implementation records it in its natchez
span.

## The flow

```scala
def price(request: PriceRequest): Flow[PricedOrder] =
  for
    ctx      <- Kleisli.ask[Fallible, RequestContext]
    customer <- required(customers.find(request.customerId),
                         AppError.CustomerNotFound(request.customerId))
    coupon   <- lift(request.couponCode.flatTraverse(coupons.find))
    perk     <- lift(loyalty.checkPerk(request.customerId, ctx.traceId))
    valid    <- fromValidated(
                  Validation.validate(request, customer, catalog, coupon, ctx.receivedAt))
    orderId  <- lift(ids.newOrderId)
    priced    = Pricing.price(valid, perk, orderId, ctx.receivedAt)
    _        <- lift(orders.save(priced))
  yield priced
```

Private helpers: `lift` (`Kleisli.liftF ∘ EitherT.liftF`), `required` (`Option` to
`AppError`), and `fromValidated`.

**`fromValidated` is the `Validated → EitherT` hinge**, and it is what the DoD wants to
see:

```scala
private def fromValidated[A](v: Validation.Result[A]): Flow[A] =
  Kleisli.liftF(EitherT.fromEither[F](v.toEither.leftMap(AppError.Validation.apply)))
```

Validation accumulates internally (`ValidatedNel`, deliberately without a `Monad`); the
moment it crosses into the flow it becomes fail-fast, because if the order is invalid
there is no point generating an id or writing to Dynamo.

**Only `Monad`, not `Parallel`.** The three reads are independent and could run
concurrently, but asking for the minimum capability is the rule: `Parallel` gets added
only when it is used. Going sequential also lets the flow short-circuit before calling
the partner when the customer does not exist. If latency ever demanded it, the change is
local — `parTupled` over the three `F[Option[_]]` before entering the `EitherT` — and
leaves the rest of the flow untouched.

**The order follows the spec's steps 1–6**, with the partner call (step 3) before
validation (step 4). That costs one network call on invalid orders; validating first
would be more efficient, but deviating from an explicitly numbered flow in the brief is
the worse bet in a review. A deliberate decision, not an oversight.

## The phase 2 defect this phase uncovered

`ValidationError.CouponNotFound` existed but was never emitted: `validate` took an
`Option[Coupon]` and treated `None` as "no coupon", without distinguishing it from "a
coupon was requested and the repo did not find it". Fixed in `c2865e3` **inside
`Validation`**, not in the flow: if the flow short-circuited with `EitherT` on seeing the
repo's `None`, an order with an unknown SKU and a nonexistent coupon would return a
single error in the 422 instead of two. "The coupon exists" is one more validation rule
and has to accumulate with the rest.

No signature change was needed: `PriceRequest` already carried the requested code.

## Tests

The strongest argument of the phase: the flow is instantiated at **`F = cats.Id`** — no
`IO`, no runtime, no `unsafeRunSync`. The error channel comes from `EitherT`, not from
`F`, so `Id` is enough for the failure paths too.

To assert that the order *was persisted*, without a single `var` (DoD #7), a second
instantiation at **`F = Writer[Chain[PricedOrder], *]`**: the `OrderRepo` stub writes to
the `Writer`'s log. **Two different `F`s and neither is `IO`** — that is what shows the
polymorphic signature was not decorative.

Cases:

1. Happy path reproducing the brief's example end to end.
2. Unknown customer → `AppError.CustomerNotFound`, short-circuiting before the partner call.
3. Unknown SKU + nonexistent coupon → `AppError.Validation` carrying **both** 422 errors.
4. Partner returns a perk → the extra discount is applied.
5. Partner degrades (`None`) → priced without a perk, no crash.
6. The order is persisted with the `IdGen`'s `orderId` and the context's `createdAt`.
7. Failed validation → **nothing is persisted**.

## Debt recorded for later phases

- **`AppError.CustomerNotFound` has no error shape in the Smithy contract.** Today only
  `ValidationException`/422 exists, so it would surface as a 500. Adding the matching
  shape to the Smithy model is phase 4 work.
- **`Kleisli` here sits right at the edge of paying for itself.** In a ~15-line flow,
  `traceId` and `receivedAt` as plain parameters would not be much worse. It stays
  because the DoD names it and because what has to be defended is the *judgement*: why it
  is in the flow and **not** wrapping the algebras or the static config.

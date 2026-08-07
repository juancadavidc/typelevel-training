# Error accumulation: `ValidatedNel` vs `Either`/`EitherT`

> Learning note — 2026-08-07

## What it is
`ValidatedNel[E, A]` is `Validated[NonEmptyList[E], A]`: a disjunction that, unlike
`Either`, **has no `Monad` instance — only `Applicative`**. That omission is deliberate,
and it is exactly what lets `mapN` / `traverse` / `tupled` evaluate *every* branch and
merge all failures into a single `NonEmptyList`.

In this repo the whole validation layer is built on one alias
(`domain/src/main/scala/com/kata/pricing/domain/Validation.scala:28`):

```scala
type Result[A] = ValidatedNel[ValidationError, A]
```

## Why it matters / when to use it
Fail-fast in `Either` is **not a policy decision, it is forced by the shape of `flatMap`**:

```scala
def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
```

`f` cannot produce the second computation until the first `A` exists. If the first step
failed there is no `A`, so there is no second computation to collect errors from. No
implementation of `flatMap` can accumulate. `Applicative`'s `ap`/`product`, by contrast,
receives both `F`s already built and is free to inspect both.

That is why the spec's 422 example — `UNKNOWN_SKU` **and** `COUPON_EXPIRED` returned
together — is reproducible with `Validated` and structurally impossible with
`Either`/`EitherT`.

### The four accumulation points in `Validation.scala`

| Where | Combinator | What it merges |
|---|---|---|
| `validateLines` (`:62`) | `traverse` | every item in the order, not just the first bad one |
| `validateLine` (`:81`) | `mapN` | `sku` + `quantity` of a single item |
| `validateCouponRules` (`:108`) | `mapN` | expired / exhausted / not-stackable |
| `validate` (`:40`) | `tupled` | line errors **with** coupon errors |

The last one is the one that makes the spec's 422 example possible at all:

```scala
(validateLines(request.items, catalog), validateCouponRules(coupon, customer.tier, now)).tupled
```

### The mental rule

```
mapN / traverse / tupled on Validated  -> accumulate
flatMap on Either / EitherT            -> short-circuit
```

Use both, each where it belongs: `Validated` **inside** validation, `EitherT` for the
outer service flow — if validation fails there is no point continuing to pricing or to
the DynamoDB write.

### The bridge between the two worlds
At the service boundary you convert once and switch abstraction:

```scala
EitherT.fromEither[F](validated.toEither.leftMap(AppError.Validation.apply))
```

`ValidatedNel[ValidationError, A].toEither` gives `Either[NonEmptyList[ValidationError], A]`,
which is exactly what `AppError.Validation` wraps
(`domain/src/main/scala/com/kata/pricing/domain/errors.scala:44`). From that point on,
short-circuiting is what you *want*.

## The important part: why two `andThen` calls remain

`andThen` is `Validated`'s own chaining combinator. It **sequences and therefore
short-circuits**, without `Validated` being a monad. Both uses in the file are genuine
data dependencies, not sloppiness:

**1. `validateLine` (`:69`) — parse before lookup.** You cannot look up a SKU in the
catalog if the SKU string did not even parse:

```scala
Sku.from(item.sku)
  .leftMap(reason => ValidationError.InvalidSku(reason, index))
  .toValidatedNel
  .andThen { parsed =>
    if catalog.contains(parsed) then parsed.validNel
    else ValidationError.UnknownSku(item.sku, index).invalidNel
  }
```

Note this `andThen` is *inside* the `sku` branch only — the `quantity` branch is still
combined with `mapN` (`:81`), so a bad SKU **and** a zero quantity on the same item still
yield two entries.

**2. `validate` (`:41`) — subtotal before minimum.** You cannot check a coupon's
`minOrderAmount` without a subtotal, and there is no trustworthy subtotal while any line
is invalid:

```scala
(validateLines(...), validateCouponRules(...)).tupled
  .andThen { (lines, validCoupon) =>
    validateMinimumAmount(validCoupon, subtotalOf(lines))
      .map(_ => ValidOrder(customer, lines, validCoupon))
  }
```

### The concrete counterexample
Items `[SKU-999 (unknown), SKU-045 x1 = 49.99]`, coupon requiring `minOrderAmount = 100.00`.

Accumulating `ORDER_BELOW_MINIMUM` here would use a subtotal of **49.99, computed from
valid lines only**. But once the user fixes `SKU-999`, the real order would likely exceed
100.00 and the coupon *would* apply — so the accumulated error would be **false**. Using
subtotal `0` is worse: it makes the error unconditional.

So the check is skipped **not because `ValidatedNel` cannot express it**, but because
asserting it would be wrong.

> Accumulate when validations are **independent**; chain only when the next one needs the
> previous one's **result**. Accumulating an error you cannot justify is noise, not coverage.

## Notes / gotchas
- Two tests in `domain/src/test/scala/com/kata/pricing/domain/ValidationSuite.scala` pin
  this design:
  - `"the coupon minimum is not evaluated when the lines are invalid"` asserts
    `UNKNOWN_SKU` is present and `ORDER_BELOW_MINIMUM` is **not** — it pins the `andThen`
    boundary.
  - `"accumulates item and coupon errors in the same response"` asserts
    `UNKNOWN_SKU` + `COUPON_EXPIRED` come back together, `codes.size == 2` — **that is the
    test that would fail if someone reached for `EitherT` here**, not from a calculation
    bug but from picking the wrong abstraction.
- `Validated` has no `flatMap` by design; if you find yourself wanting one, you either
  have a real data dependency (use `andThen`, scoped as tightly as possible) or you have
  reached the point where the flow should become `EitherT`.
- `Validated.condNel(cond, a, e)` is the terse way to express a single boolean rule as a
  `Result[A]` — used for all three coupon rules and for the minimum check.
- `traverse` on a `NonEmptyList` returns `Result[NonEmptyList[_]]`, preserving non-emptiness,
  so the empty-order case is handled separately with `NonEmptyList.fromList` (`:57`).
- Accumulation only works because the error channel is a `Semigroup`. `NonEmptyList` is;
  a bare `ValidationError` is not — hence `ValidatedNel` rather than `Validated`.

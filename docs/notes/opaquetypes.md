# Opaque Types (Scala 3)

> Learning note — 2026-08-07

## What it is
An **opaque type** is a Scala 3 type alias that, **outside its defining scope**, is a
distinct type incompatible with the underlying type, yet **adds no runtime cost** (no
wrapping/boxing like a `case class`).

## Why it matters / when to use it
It gives type safety for domain *newtypes* (identifiers, values) with zero overhead:

- A plain `type` is transparent → no protection against mixing values.
- A `case class` protects but adds an extra object in memory.
- An `opaque type` combines the best of both: compile-time protection + zero runtime cost.

Ideal for modeling `CustomerId`, `CouponCode`, `OrderId`, `Money`, etc. and avoiding
accidentally swapping an `OrderId` for a `CustomerId` even when both are `String`.

## Example
```scala
object domain:
  opaque type CustomerId = String

  object CustomerId:
    // smart constructor with validation (mirrors @length(min: 1) in the Smithy model)
    def from(s: String): Either[String, CustomerId] =
      if s.nonEmpty then Right(s) else Left("empty customerId")

    extension (id: CustomerId)
      def value: String = id

import domain.*
val id  = CustomerId.from("abc")  // Either[String, CustomerId]
// val s: String = id             // ❌ does not compile: CustomerId is not String here
```

- **Inside** the defining scope, `CustomerId` and `String` are interchangeable.
- **Outside**, they are distinct types → the compiler protects you.
- **At runtime** it is literally a `String`.

## Notes / gotchas
- Access to the underlying value is usually exposed with an `extension` (`.value`) or a
  method on the companion.
- Constructors live in the companion `object` so they can validate (smart constructors).
- smithy4s generates its own newtypes for the Smithy types, so sometimes you don't need
  to hand-write the opaque type — but knowing it helps you understand what's generated
  and how to model extra domain values.
- They can be constrained with bounds: `opaque type Positive <: Int = Int`.

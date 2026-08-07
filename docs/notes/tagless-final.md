# Tagless Final

> Learning note — 2026-08-07

## What it is

A pattern for writing polymorphic code over the effect type `F[_]` instead of
tying logic to a concrete type like `IO`. Operations are defined as an *algebra*
(an interface) parameterized by a type constructor `F[_]`, and the concrete
interpreter is chosen only at the edge of the application (the composition root).

```scala
trait PricingRepo[F[_]]:
  def find(id: OrderId): F[Option[Order]]
  def save(order: Order): F[Unit]
```

Business logic depends only on the abstraction plus the type classes it needs
(`Monad`, `Sync`, ...), never on what `F` actually is:

```scala
def priceOrder[F[_]: Monad](repo: PricingRepo[F])(id: OrderId): F[Either[Error, Money]] =
  ...
```

## Why "tagless"

It comes from the *final tagless encoding*, as opposed to the *initial encoding*
(an ADT whose cases represent the operations and are interpreted later). In
tagless final there is no intermediate data structure with "tags": each operation
is a method that directly produces `F[A]`, and the "interpreter" is the type class
instance you pass in.

## Why it matters / when to use it

- **Decouples logic from the effect** → in this kata, `domain/` never imports `IO`.
- **Testable**: instantiate `F` with a test effect or `TestControl` without touching
  the logic.
- **Least privilege**: if a method only asks for `Monad[F]`, it cannot perform
  arbitrary side effects.

Rule of thumb:

> If something performs **effects** (IO, network, time, randomness) **and** you want
> to swap it in tests or change the backend → tagless final algebra. If it is pure
> computation → a plain function.

Do **not** use it for pure logic (validation, pricing math in `domain/`): those are
plain functions returning `Either`/`Validated`. Adding `F[_]` there is over-engineering.

## Example: one logic, many interpreters

```scala
trait Console[F[_]]:
  def readLine: F[String]
  def println(s: String): F[Unit]

def program[F[_]: Monad](C: Console[F]): F[Unit] =
  for
    name <- C.readLine
    _    <- C.println(s"Hola, $name")
  yield ()

// Interpret to IO only at the end:
val live: Console[IO] = new Console[IO]:
  def readLine = IO.readLine
  def println(s: String) = IO.println(s)

program(live) // : IO[Unit]
```

The same `Console[F[_]]` works with any arity-1 type constructor, as long as the
required evidence (`Monad[F]`) exists:

```scala
val futureConsole: Console[Future] = new Console[Future]:
  def readLine = Future(scala.io.StdIn.readLine())
  def println(s: String) = Future(Console.println(s))

val taskConsole: Console[Task] = new Console[Task]:      // monix / ZIO Task
  def readLine = Task(scala.io.StdIn.readLine())
  def println(s: String) = Task(Console.println(s))

program(futureConsole)  // : Future[Unit]
program(taskConsole)    // : Task[Unit]
program(live)           // : IO[Unit]
```

## Where else to use it (with kata examples)

1. **Repositories / persistence** — abstract data access away from DynamoDB:

   ```scala
   trait OrderRepo[F[_]]:
     def get(id: OrderId): F[Option[Order]]
     def put(order: Order): F[Unit]
   ```

   `service/` implements it over DynamoDB + `IO`; tests use a `Ref[F, Map[...]]`
   in memory, no LocalStack.

2. **External clients (the partner client)**:

   ```scala
   trait PartnerClient[F[_]]:
     def quote(order: Order): F[PartnerQuote]
   ```

   The timeout/retry test with `TestControl` (DoD rule 5) needs no real server: use
   a fake instance that simulates latency.

3. **Logging / tracing / telemetry** — ask for the capability instead of a static
   logger. natchez's `Trace[F]` is exactly this: an algebra that gives spans without
   binding you to a backend (Jaeger, XRay, noop in tests).

   ```scala
   trait Logger[F[_]]:
     def info(msg: String): F[Unit]
   ```

4. **Clock / time / randomness (sources of non-determinism)** — everything impure
   becomes an algebra. cats-effect already ships `Clock[F]`, `Random[F]`,
   `UUIDGen[F]`; pin them in tests for determinism.

   ```scala
   trait Clock[F[_]]:
     def now: F[Instant]
   ```

5. **The outbox / event publisher** — in the lambda (DynamoDB Streams → Kinesis) the
   producer is an algebra, letting you test idempotency (at-least-once delivery)
   without real Kinesis:

   ```scala
   trait EventPublisher[F[_]]:
     def publish(event: PricingEvent): F[Unit]
   ```

6. **Combine several capabilities in one `F`** — logic asks for exactly what it needs,
   and the composition root satisfies all of them with a single `IO`:

   ```scala
   def priceAndPublish[F[_]: Monad](
       repo: OrderRepo[F],
       partner: PartnerClient[F],
       pub: EventPublisher[F],
       log: Logger[F]
   )(id: OrderId): F[Either[PricingError, Money]] =
     ...
   ```

## Notes / gotchas

- The abstraction only works **if `F` satisfies the constraints you require**.
  A smaller constraint (`Monad` vs `Sync` vs `Temporal`) means more valid types and
  less power to misbehave.
- `Future` has a cats `Monad`, but it is impure: it runs on construction and breaks
  the laws of a suspended effect. It compiles and "works", but you lose referential
  transparency and execution control. Prefer `IO`/`Task`.
- A type with no `Monad` instance will not even compile — the compiler enforces the
  contract.
- Modern cats-effect style: instead of always defining a `trait` algebra, request
  capabilities via standard type classes (`Sync`, `Async`, `Temporal`). Same tagless
  final philosophy with the stack's type classes.
- In this kata: `domain/` stays pure (no effectful `F`), and tagless final algebras
  (repos, partner, publisher, tracing) live at the boundary interpreted by `service/`
  and `lambda/` over `IO`.

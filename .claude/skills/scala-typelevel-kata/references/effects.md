# Efectos, polimorfismo en F[_] y pureza

El objetivo de esta capa en el kata: la lógica de negocio no sabe qué efecto la ejecuta.
`IO` aparece una sola vez, en el composition root (`Main`). Todo lo demás es abstracto.

## El punto de partida: qué transfiere desde Monix

Si vienes de Monix `Task`, la mayoría de los conceptos transfieren directo:

| Monix | Cats-Effect 3 | Nota |
|---|---|---|
| `Task[A]` | `IO[A]` | Prácticamente el mismo modelo mental |
| `Task.eval` / `Task.delay` | `IO.delay` / `IO(...)` | Suspensión perezosa |
| `Scheduler` | `IORuntime` | Se provee una vez, en `IOApp` |
| `Task.parMap2` | `(a, b).parMapN` | Vía `Parallel`, sintaxis de cats |
| `Task.racePair` | `IO.racePair` | Igual |
| `Bracket` / `.bracket` | `Resource` | **Aquí sí cambia el estilo** — ver abajo |
| `MVar`, `Atomic` | `Deferred`, `Ref` | `Ref` es el 90% de los casos |

Lo genuinamente nuevo: **`Resource` como valor de primera clase**. En Monix el
`bracket` es un método sobre la acción; en CE3 `Resource[F, A]` es un valor que
se compone con `flatMap` y `parZip` antes de ejecutarse. Eso es lo que permite
construir todo el grafo de dependencias (clientes AWS, pool HTTP, tracing) como
una sola expresión y liberarlo en orden inverso automáticamente.

## Por qué la lógica va polimórfica en F[_]

El DoD exige `EitherT`/`Kleisli` sobre `F[_]` en vez de `IO` directo. La razón no
es purismo: es que una firma como

```scala
def priceOrder[F[_]: Monad](req: PriceRequest): EitherT[F, AppError, PricedOrder]
```

declara en el tipo que la función **sólo secuencia** — no lanza hilos, no lee el
reloj, no toca la red. Si necesitara el reloj, tendrías que pedir `Clock[F]`
explícitamente, y eso se ve en la firma. Con `IO` hardcodeado no puedes distinguir
una función pura de una que borra la base de datos: `IO[Unit]` no dice nada.

El beneficio práctico en el kata: los tests instancian `F = Either`, `F = Id` o
`F = IO` según convenga, sin runtime de efectos cuando no hace falta.

Pide la capacidad mínima. Si sólo secuencias, `Monad`. Si acumulas errores en
paralelo, `Parallel`. Si necesitas el reloj, `Clock`. Añadir `Async[F]` "por si
acaso" tira a la basura la garantía que estabas comprando.

## Validated vs EitherT: la distinción que decide el 422

Esto es la trampa más fácil de pisar en este kata, y sale directo en la respuesta
de la API. El PDF exige acumular **todos** los errores de validación:

```json
{"errors": [{"code": "UNKNOWN_SKU", ...}, {"code": "COUPON_EXPIRED", ...}]}
```

`EitherT` no puede hacer eso. Su `flatMap` corta en el primer `Left` — es
fail-fast por diseño, porque el segundo paso puede depender del primero. Si
validas con `EitherT` obtienes un solo error y el test del 422 con dos errores falla.

Para acumular necesitas `ValidatedNel[E, A]` y su `Applicative`:

```scala
import cats.data.{Validated, ValidatedNel, NonEmptyList}
import cats.syntax.all.*

def validateItem(raw: RawItem, catalog: Catalog): ValidatedNel[ValidationError, OrderItem] =
  (validateSku(raw.sku, catalog), validateQuantity(raw.quantity))
    .mapN(OrderItem.apply)      // mapN acumula ambos lados; flatMap cortaría

def validateOrder(req: PriceRequest, catalog: Catalog, coupon: Option[Coupon])
    : ValidatedNel[ValidationError, ValidOrder] =
  (
    req.items.traverse(validateItem(_, catalog)),   // traverse acumula sobre la lista
    validateCoupon(coupon, req.items)
  ).mapN(ValidOrder.apply)
```

La regla mental: **`mapN`/`traverse` sobre `Validated` acumulan; `flatMap` sobre
`EitherT` corta.** Usa las dos, cada una donde corresponde.

El puente entre ambos mundos, en la frontera del servicio:

```scala
EitherT.fromEither[F](
  validateOrder(req, catalog, coupon).toEither.leftMap(AppError.Validation.apply)
)
```

Así la validación acumula internamente y el flujo general sigue siendo fail-fast:
si la validación falla, no tiene sentido seguir a calcular precio ni a escribir en Dynamo.

## Álgebras: la frontera entre puro y efectivo

Define las dependencias como traits sobre `F[_]`. El núcleo depende del trait,
nunca de la implementación:

```scala
// en el módulo domain — sin imports de AWS ni http4s
trait CustomerRepo[F[_]]:
  def find(id: CustomerId): F[Option[Customer]]

trait CouponRepo[F[_]]:
  def find(code: CouponCode): F[Option[Coupon]]

trait LoyaltyClient[F[_]]:
  def checkPerk(id: CustomerId): F[Option[Perk]]
```

Las implementaciones (DynamoDB, http4s) viven en el módulo `service`. El compilador
garantiza la separación: `domain` no tiene esas librerías en su classpath.

En los tests, una implementación de mentira es trivial y no necesita mocking:

```scala
val stubRepo = new CustomerRepo[IO]:
  def find(id: CustomerId) = IO.pure(Some(Customer(id, Tier.Gold, None, ts)))
```

## Kleisli/ReaderT: úsalo con criterio

El PDF pide enhebrar config con `Kleisli`/`ReaderT` en vez de pasarla por cada
firma. Es una técnica legítima, pero si envuelves *todo* en `Kleisli` el código se
vuelve ruidoso y los mensajes de error del compilador se degradan.

Dónde rinde de verdad: el contexto que atraviesa muchas capas sin que la mayoría
lo use — trace id, request id, tenant. Para config estática hay algo más simple y
igual de válido: cargarla una vez con ciris en el composition root y pasarla al
constructor de cada implementación. La config no cambia entre requests, así que
`ReaderT` sobre ella es maquinaria sin retorno.

Un uso honesto y suficiente para demostrar la técnica:

```scala
type Ctx[F[_], A] = Kleisli[F, RequestContext, A]

def priceOrder[F[_]: Monad: Clock](req: PriceRequest): Kleisli[F, RequestContext, Result] =
  Kleisli { ctx => ... }   // ctx trae traceId, disponible sin ensuciar cada firma
```

Si el mentor pregunta por qué no está en todas partes, la respuesta correcta es
justamente ésa: `Kleisli` para contexto por request, constructor para config estática.
Eso demuestra criterio, no ignorancia.

## Resource para todo lo que se cierra

El DoD lo exige explícitamente: clientes DynamoDB/Kinesis vía `Resource`, nunca
abiertos y cerrados a mano. La razón es que `Resource` garantiza la liberación
incluso ante cancelación o excepción, que es precisamente el caso que un
`try/finally` a mano suele arruinar.

```scala
object Clients:
  def dynamo[F[_]: Sync](cfg: AwsConfig): Resource[F, DynamoDbAsyncClient] =
    Resource.fromAutoCloseable(Sync[F].delay {
      val b = DynamoDbAsyncClient.builder().region(cfg.region)
      cfg.endpointOverride.foreach(uri => b.endpointOverride(uri))  // LocalStack
      b.build()
    })
```

Y en el composition root todo se compone como una sola expresión:

```scala
object Main extends IOApp.Simple:
  def run: IO[Unit] =
    (for
      cfg    <- Resource.eval(Config.load[IO])
      dynamo <- Clients.dynamo[IO](cfg.aws)
      ep     <- Resource.eval(Log.entrypoint[IO]("pricing"))
      routes <- PricingRoutes[IO](deps)          // smithy4s
      _      <- EmberServerBuilder.default[IO].withHttpApp(routes.orNotFound).build
    yield ()).useForever
```

Ese `for` es el único lugar del proyecto donde aparece `IO`. Es el criterio de
verificación más simple del DoD: `grep -rn "IO" domain/src` no debería dar nada.

## Errores como ADT

```scala
enum AppError:
  case Validation(errors: NonEmptyList[ValidationError])
  case CustomerNotFound(id: CustomerId)
  case Persistence(cause: String)

enum ValidationError(val code: String, val field: String, val message: String):
  case UnknownSku(sku: String, idx: Int)
      extends ValidationError("UNKNOWN_SKU", s"items[$idx].sku", s"$sku does not exist")
  case CouponExpired(code: String, on: Instant)
      extends ValidationError("COUPON_EXPIRED", "couponCode", s"Coupon $code expired on $on")
```

Un `enum` cerrado hace que el compilador avise si el mapeo a errores de Smithy
olvida un caso — mejor que un `sealed trait` disperso o strings sueltos.

## natchez: spans que atraviesan el for-comprehension

Pide `Trace[F]` como capacidad y envuelve las operaciones con significado propio.
Lo que se evalúa aquí es que el span **propague** por toda la cadena, no que exista
un span decorativo en el handler:

```scala
def priceOrder[F[_]: Monad: Trace](req: PriceRequest): EitherT[F, AppError, PricedOrder] =
  EitherT.liftF(Trace[F].span("price-order") {
    for
      customer <- Trace[F].span("fetch-customer")(repo.find(req.customerId))
      coupon   <- Trace[F].span("fetch-coupon")(coupons.find(req.couponCode))
    yield (customer, coupon)
  }).flatMap { ... }
```

Localmente el entrypoint `Log` imprime los spans a consola, que es suficiente para
practicar la composición. En producción el mismo código exporta a Datadog sin cambios.

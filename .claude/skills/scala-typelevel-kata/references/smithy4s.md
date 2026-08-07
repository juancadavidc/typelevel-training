# smithy4s: el contrato como fuente de verdad

El PDF lo llama *"the most important new skill in the kata"* y exige que el modelo
Smithy sea **el primer artefacto que escribes**, no algo añadido después sobre rutas
existentes. La razón: así funciona toda la superficie de API del cliente (api-registry).

Si vienes de circe + rutas http4s escritas a mano, el cambio mental es: ya no defines
case classes con `derives Codec` ni haces pattern matching sobre rutas. Escribes el
contrato en IDL, la codegen produce las clases y el router, y tú sólo implementas un
trait. Los codecs los genera smithy4s (usando jsoniter por debajo) — no escribes
`Encoder`/`Decoder` para los DTOs de la API.

## Dónde vive el modelo

Por convención el plugin busca en `src/main/smithy`:

```
service/src/main/smithy/pricing.smithy
```

## El modelo del kata

```smithy
$version: "2"

namespace com.kata.pricing

use alloy#simpleRestJson

@simpleRestJson
service PricingService {
  version: "1.0.0"
  operations: [PriceOrder]
}

@http(method: "POST", uri: "/orders/price", code: 200)
operation PriceOrder {
  input: PriceOrderRequest
  output: PricedOrderResponse
  errors: [ValidationException]
}

structure PriceOrderRequest {
  @required customerId: CustomerId
  @required items: OrderItemList
  couponCode: CouponCode
}

structure PricedOrderResponse {
  @required orderId: OrderId
  @required customerId: CustomerId
  @required status: OrderStatus
  @required items: PricedItemList
  @required subtotal: Money
  @required discountAmount: Money
  @required total: Money
  couponApplied: CouponCode
  @required createdAt: Timestamp
}

@error("client")
@httpError(422)
structure ValidationException {
  @required errors: ValidationErrorList
}

structure ValidationErrorDetail {
  @required code: String
  @required field: String
  @required message: String
}

list ValidationErrorList { member: ValidationErrorDetail }
list OrderItemList       { member: OrderItemInput }
list PricedItemList      { member: PricedItem }

structure OrderItemInput {
  @required sku: String
  @required quantity: Integer
}

structure PricedItem {
  @required sku: String
  @required quantity: Integer
  @required unitPrice: Money
  @required lineTotal: Money
}

enum OrderStatus { PRICED = "PRICED" }

@length(min: 1)
string CustomerId

@length(min: 1)
string CouponCode

string OrderId

bigDecimal Money
```

Detalles que importan:

- **`alloy#simpleRestJson`** es el protocolo que hace que smithy4s genere un servidor
  http4s con JSON. Sin la anotación de protocolo no se genera transporte.
- **`bigDecimal Money`** — no uses `double` para dinero. El PDF muestra `19.99` y
  `8.99`; en `Double` el 10% de `89.97` no da exactamente `8.997`, y los property
  tests de ScalaCheck lo destapan.
- **Los `string` con nombre** (`CustomerId`, `CouponCode`) generan newtypes en Scala,
  no alias de `String`. Eso ya te acerca al requisito de opaque types del DoD del lado
  de la API; el dominio tendrá los suyos propios.
- **`@httpError(422)`** conecta el error de Smithy con el código HTTP que pide el PDF.
- **`@required`** decide `Option` o no en el Scala generado. Revísalo con cuidado:
  cambiarlo después obliga a tocar todo lo que consume el tipo.

## Wiring del build

En `project/plugins.sbt`:

```scala
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.11")
```

El group id **sigue siendo `com.disneystreaming.smithy4s`** — si un tutorial te manda
a `software.amazon.smithy4s`, está equivocado (ese artefacto no existe). Y la serie
actual es la **0.19.x**, no la 0.18 que aparece en documentación más antigua. Hay
plugin tanto para sbt 1.x como para sbt 2.x.

En `build.sbt`, sólo en el módulo que tiene el modelo:

```scala
lazy val service = project
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value
    )
  )
```

`smithy4sVersion.value` viene del plugin: mantiene alineadas la versión de la codegen
y la de la librería, que es una fuente típica de errores raros al desalinearse.

Comprobación temprana: `sbt compile` debe generar fuentes bajo
`target/scala-3.x/src_managed/`. Si ahí no aparece nada, el problema es de wiring
(plugin no habilitado, modelo en otra ruta, falta la dependencia de alloy) y conviene
resolverlo antes de escribir lógica. Por eso el roadmap pone smithy4s en la Fase 1:
es el mayor riesgo técnico del kata.

En Metals/IntelliJ, tras la primera codegen hay que refrescar el proyecto para que el
IDE vea las clases generadas — si no, el editor marca errores donde el compilador no.

## Implementar el servicio

La codegen produce un trait `PricingService[F[_]]`. Tú lo implementas:

```scala
import smithy4s.*

final class PricingImpl[F[_]: Monad: Clock: Trace](
  customers: CustomerRepo[F],
  coupons: CouponRepo[F],
  loyalty: LoyaltyClient[F],
  orders: OrderRepo[F]
) extends PricingService[F]:

  def priceOrder(
    customerId: CustomerId,
    items: List[OrderItemInput],
    couponCode: Option[CouponCode]
  ): F[PricedOrderResponse] =
    Pricing.priceOrder[F](toCommand(customerId, items, couponCode))
      .foldF(
        err => Monad[F].raiseError(toSmithyError(err)),   // AppError → ValidationException
        ok  => toResponse(ok).pure[F]                     // vía chimney
      )
```

Fíjate en la forma: el método generado devuelve `F[Response]`, mientras tu lógica
devuelve `EitherT[F, AppError, _]`. El `foldF` es exactamente la frontera donde el
error tipado del dominio se convierte en el error del protocolo. Ese es el punto de
traducción, y conviene que sea el único.

## Montar las rutas

```scala
object Routes:
  def apply[F[_]: Async](impl: PricingService[F]): Resource[F, HttpRoutes[F]] =
    SimpleRestJsonBuilder.routes(impl).resource
```

Y al servidor:

```scala
EmberServerBuilder.default[F]
  .withHost(host).withPort(port)
  .withHttpApp(routes.orNotFound)
  .build
```

Eso es todo el transporte. No escribes `case req @ POST -> Root / "orders" / "price"`
en ninguna parte — si te encuentras escribiendo rutas a mano, algo se desvió del
enfoque que pide el PDF.

## El error tipado y el 422

Para que el 422 salga con la forma que muestra el PDF, la excepción generada se lanza
en `F` y smithy4s la serializa según el `@httpError`:

```scala
def toSmithyError(e: AppError): Throwable = e match
  case AppError.Validation(errors) =>
    ValidationException(errors.toList.map(v =>
      ValidationErrorDetail(code = v.code, field = v.field, message = v.message)))
  case AppError.CustomerNotFound(id) =>
    ValidationException(List(ValidationErrorDetail("CUSTOMER_NOT_FOUND", "customerId", s"$id")))
  ...
```

Aquí se nota si la validación usó `Validated` o `EitherT`: con `EitherT` la lista
tendrá siempre un elemento y el ejemplo del PDF con dos errores simultáneos no se
reproduce. Ver `references/effects.md`.

## Cliente generado (útil para tests end-to-end)

El mismo modelo genera cliente, así que un test de integración puede llamar al
servicio con tipos en vez de armar JSON a mano:

```scala
SimpleRestJsonBuilder(PricingService)
  .client(httpClient)
  .uri(uri"http://localhost:8080")
  .resource
```

Si el cliente compila contra el servidor, el contrato está honrado por construcción —
que es precisamente la ventaja de haber empezado por el IDL.

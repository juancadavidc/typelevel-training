# chimney: transformaciones DTO ↔ dominio ↔ persistencia

El DoD exige que **todas** las transformaciones entre capas pasen por chimney, y que
donde haga falta un mapeo custom sea deliberado y visible, no una copia a mano
disfrazada de transformación.

## Qué problema resuelve

En este kata el mismo dato vive en tres formas: el DTO generado por smithy4s, el
modelo de dominio con opaque types y enums, y el ítem de DynamoDB. Copiarlos a mano
son decenas de líneas de `Foo(a = b.a, c = b.c, ...)` — ruido donde es fácil colar un
bug silencioso (intercambiar dos campos del mismo tipo, olvidar uno nuevo).

chimney deriva esa copia en tiempo de compilación cuando los campos coinciden, y
**falla al compilar** cuando no. Esa es la ventaja real: añadir un campo al dominio
rompe el build en el punto exacto donde falta el mapeo, en vez de propagarse como
un `null` o un valor por defecto.

```scala
import io.scalaland.chimney.dsl.*

val dto: PricedOrderResponse = domainOrder.transformInto[PricedOrderResponse]
```

## Cuándo hace falta configuración

Rara vez las tres formas coinciden campo a campo. Los casos de este kata:

**Campos derivados** — `lineTotal` no existe en el dominio si lo calculas al vuelo:

```scala
val response = pricedOrder
  .into[PricedOrderResponse]
  .withFieldComputed(_.items, _.items.map { i =>
    PricedItem(i.sku.value, i.quantity, i.unitPrice, i.unitPrice * i.quantity)
  })
  .transform
```

**Renombrados** — el PDF llama `couponApplied` en la respuesta a lo que en dominio es
`couponCode`:

```scala
.withFieldRenamed(_.couponCode, _.couponApplied)
```

**Opaque types ↔ String** — la frontera con persistencia. Define los `Transformer`
una vez, como given, y chimney los usa en todas las derivaciones:

```scala
object transformers:
  given Transformer[CustomerId, String] = _.value
  given Transformer[String, CustomerId] = CustomerId.apply
  given Transformer[Sku, String]        = _.value
  // ... importa este objeto donde transformes
```

Sin esto, cada `.transformInto` que cruce un opaque type pide configuración local y
acabas repitiendo lo mismo en diez sitios.

**Enums ↔ String** — para el `status` en DynamoDB:

```scala
given Transformer[OrderStatus, String] = _.toString
given Transformer[String, OrderStatus] =
  s => OrderStatus.valueOf(s)   // ojo: lanza si el string no matchea
```

Para la dirección String→enum, si el dato puede venir corrupto de la base, es más
honesto un `PartialTransformer`, que devuelve un resultado con errores en vez de
lanzar:

```scala
given PartialTransformer[String, OrderStatus] =
  PartialTransformer.fromEitherString(s =>
    OrderStatus.values.find(_.toString == s).toRight(s"status desconocido: $s"))
```

## Qué NO hacer

Una salida fácil que anula el propósito: `withFieldConst` sobre cada campo, o
transformadores que en realidad son la copia a mano con otra sintaxis. Si el mapeo
está enumerando todos los campos uno por uno, chimney no está aportando nada y en la
revisión se nota. El objetivo es que la derivación automática cubra el 90% y la
configuración explícita marque exactamente las excepciones.

## Test de round-trip

Vale la pena una propiedad: dominio → persistencia → dominio conserva el dato. Atrapa
transformadores asimétricos, que es el bug típico cuando hay dos direcciones:

```scala
test("round-trip dominio → item → dominio") {
  forall(genPricedOrder) { order =>
    val item = order.transformInto[OrderItem]
    expect(item.transformInto[PricedOrder] == order)
  }
}
```

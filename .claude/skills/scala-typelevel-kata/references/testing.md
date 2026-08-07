# Testing: weaver, ScalaCheck, WireMock, TestControl, testcontainers

El PDF pide weaver explícitamente y advierte "no reaches for munit/specs2 out of habit".
Hay cuatro requisitos distintos, y cada uno prueba algo que los otros no.

## weaver: por qué y cómo

weaver está construido sobre cats-effect: los tests **son** valores `IO`, se ejecutan
en paralelo por defecto, y los recursos compartidos se expresan con `Resource`. Eso
encaja con el resto del kata — no hay que envolver efectos en `unsafeRunSync()`.

**Coordenadas correctas:** `org.typelevel::weaver-cats` y `org.typelevel::weaver-scalacheck`,
versión **0.13.0**. El group viejo `com.disneystreaming` está muerto desde 0.8.4 (enero
2024) y sale mucho en tutoriales — si lo usas, te pierdes dos años de arreglos.

En 0.13.0 se eliminaron las suites `Mutable*`, así que ejemplos antiguos de internet no
compilarán tal cual. Es un cambio rompiente conocido y hay migración con scalafix; como
empiezas de cero, simplemente usa la API nueva desde el principio.

Test simple:

```scala
import weaver.*

object PricingSuite extends SimpleIOSuite:

  pureTest("subtotal is the sum of line totals") {
    val order = Pricing.compute(fixtures.validOrder, None)
    expect(order.subtotal == BigDecimal("89.97"))
  }

  test("fetches the customer tier") {
    for
      result <- Pricing.priceOrder[IO](req).value
    yield expect(result.isRight)
  }
```

`pureTest` para lo que no necesita efectos (todo el núcleo puro debería usar esto —
es la prueba viva de que la capa es pura). `test` cuando devuelves `IO`.

Recursos compartidos entre tests de una suite:

```scala
object IntegrationSuite extends IOSuite:
  override type Res = DynamoDbAsyncClient
  override def sharedResource: Resource[IO, Res] = Clients.dynamo[IO](testConfig)

  test("writes and reads an order") { client =>
    ...   // el cliente llega como parámetro; se libera al final de la suite
  }
```

Combinadores útiles: `expect.all(a, b, c)` acumula varias aserciones en vez de cortar
en la primera; `expect.eql` da mejor mensaje de diff que `==` en tipos con `Eq`.

## ScalaCheck: propiedades del pricing

El PDF nombra dos propiedades concretas: el precio final nunca es negativo y nunca
excede el total pre-descuento. Son buenas porque son invariantes del dominio, no
re-implementaciones del cálculo.

```scala
import weaver.scalacheck.*
import org.scalacheck.Gen

object PricingProps extends SimpleIOSuite with Checkers:

  val genItem: Gen[OrderItem] = for
    sku   <- Gen.identifier.map(s => Sku(s"SKU-$s"))
    qty   <- Gen.choose(1, 100)
    price <- Gen.choose(1, 100000).map(c => BigDecimal(c) / 100)  // céntimos → nunca binario
  yield OrderItem(sku, qty, price)

  test("el total nunca es negativo ni supera el subtotal") {
    forall(Gen.nonEmptyListOf(genItem)) { items =>
      val priced = Pricing.compute(items, someCoupon)
      expect.all(
        priced.total >= BigDecimal(0),
        priced.total <= priced.subtotal
      )
    }
  }
```

**Por qué generar céntimos y no `Double`:** un generador de `Double` produce valores
como `0.1 + 0.2 != 0.3`, y el test falla por aritmética binaria, no por un bug real.
Generas enteros de céntimos y divides — así el dominio y el generador coinciden en
representación exacta. Ésta es la razón por la que todo el dinero del kata va en
`BigDecimal`: el PDF muestra `19.99` y `8.99`, y un descuento del 10% sobre `89.97`
en `Double` no da exactamente `8.997`.

Un caso límite que vale la pena cubrir explícitamente: un cupón del 100% debe dar
total `0`, no negativo ni `-0.00`.

## WireMock: los tres casos del partner

El PDF pide happy path, timeout y 5xx, y exige que en los tres el servicio
**degrade sin aplicar el perk, sin crashear**. Eso es lo que se evalúa: que la
llamada al partner sea opcional por diseño, no que el stub responda.

```scala
object LoyaltySuite extends IOSuite:
  override type Res = WireMockServer
  override def sharedResource: Resource[IO, WireMockServer] =
    Resource.make(IO {
      val s = new WireMockServer(options().dynamicPort())
      s.start(); s
    })(s => IO(s.stop()))

  test("5xx → sin perk, sin fallo") { wm =>
    wm.stubFor(get(urlPathEqualTo("/perks/cust-123"))
      .willReturn(aResponse().withStatus(503)))
    for
      result <- client(wm).checkPerk(CustomerId("cust-123"))
    yield expect(result.isEmpty)      // degradó: None, no una excepción
  }

  test("timeout → sin perk, sin fallo") { wm =>
    wm.stubFor(get(urlPathEqualTo("/perks/cust-123"))
      .willReturn(aResponse().withFixedDelay(5000)))
    for
      result <- client(wm).checkPerk(CustomerId("cust-123"))
    yield expect(result.isEmpty)
  }
```

La implementación que hace pasar esto tiene la forma:

```scala
def checkPerk(id: CustomerId): F[Option[Perk]] =
  http.expect[PerkResponse](req)
    .timeout(cfg.partnerTimeout)
    .map(_.toPerk.some)
    .handleError(_ => None)      // cualquier fallo del partner → sin perk
```

`handleError` (no `handleErrorWith`) porque no hay nada que reintentar en el camino
degradado. El perk es un extra: si el partner no responde, el pedido se cotiza igual.

## TestControl: el test de tiempo virtual

Este es un bullet propio del DoD y el que más se malinterpreta: *"al menos un test
de weaver ejercita el control de tiempo de IO directamente (timeout/retry alrededor
de la llamada al partner), no sólo un valor mockeado afirmado una vez."*

O sea: no basta stubbear un retraso y esperar 5 segundos reales. Hay que usar el
scheduler de prueba de cats-effect, que **avanza el reloj sin esperar**. Un test que
verifica un timeout de 30s corre en microsegundos:

```scala
import cats.effect.testkit.TestControl

test("el timeout dispara exactamente a los 2s y degrada a None") {
  val nunca = IO.never[Option[Perk]]        // partner que no responde jamás
  val bajoPrueba = nunca.timeout(2.seconds).handleError(_ => None)

  TestControl.executeEmbed(bajoPrueba).map { r =>
    expect(r.isEmpty)
  }
}
```

Con control más fino, para comprobar que *antes* del límite todavía no hay resultado:

```scala
TestControl.execute(bajoPrueba).flatMap { control =>
  for
    _  <- control.tick
    _  <- control.advanceAndTick(1.second)
    r1 <- control.results
    _  <- IO(expect(r1.isEmpty))            // a 1s: aún nada
    _  <- control.advanceAndTick(1.second)
    r2 <- control.results
  yield expect(r2.nonEmpty)                 // a 2s: ya resolvió
}
```

Lo mismo aplica si añades reintentos con backoff: `TestControl` te permite afirmar
que el tercer intento ocurre en el instante esperado, sin que el test dure minutos.

Es el test que mejor demuestra que entendiste cats-effect, porque sólo funciona si
la lógica pidió el reloj a `F` en vez de usar `Thread.sleep` o el reloj del sistema.

## testcontainers-scala + LocalStack: integración en CI

El PDF distingue dos cosas que conviene no mezclar:

- **docker-compose** → el bucle manual de desarrollo, para hurgar con `awslocal`.
- **testcontainers** → los tests de integración automáticos, vía la tarea `test` normal en CI.

testcontainers levanta su propio contenedor por ejecución, con puertos aleatorios, y
lo tira al final. Eso hace los tests reproducibles y paralelizables, sin depender de
que alguien haya corrido `make up` antes.

```scala
object DynamoIntegrationSuite extends IOSuite:
  override type Res = (LocalStackContainer, DynamoDbAsyncClient)

  override def sharedResource: Resource[IO, Res] =
    for
      c <- Resource.make(IO {
             val c = LocalStackContainer(services = List(Service.DYNAMODB, Service.KINESIS))
             c.start(); c
           })(c => IO(c.stop()))
      client <- Clients.dynamo[IO](configFrom(c))
      _      <- Resource.eval(createTables(client))    // el esquema, antes de los tests
    yield (c, client)
```

Separa las suites de integración de las unitarias para poder correr sólo las rápidas
en el bucle de desarrollo — por convención, un sufijo (`*IntegrationSuite`) y un
filtro en sbt, o un módulo `it` aparte.

## Qué cubrir, en orden de valor

1. Propiedades del pricing (ScalaCheck) — el núcleo puro, sin efectos.
2. Validación acumulativa: dos errores simultáneos producen **dos** entradas en el 422.
   Es el test que atrapa el error de haber usado `EitherT` donde iba `Validated`.
3. Los tres casos del partner (WireMock) + el de `TestControl`.
4. Round-trip de chimney: dominio → persistencia → dominio conserva los datos.
5. Integración: escribir en Dynamo, leer el stream, ver el evento en Kinesis.

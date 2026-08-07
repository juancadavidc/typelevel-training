# AWS: CDK, LocalStack, DynamoDB Streams, Kinesis

El PDF trata el CDK como entregable de primera clase: *"los mentores deberían revisar
el código CDK también, no sólo el Scala"*. No es andamiaje — se revisa igual que el Scala.

## La contradicción del PDF sobre el outbox (leer antes de escribir la Lambda)

El documento pide dos arquitecturas incompatibles y hay que elegir una a conciencia:

- **Pág. 4 (Data Model):** *"No hay tabla ni ítem de outbox separado, y no hay
  TransactWriteItems: esto es lo que te compra el change-data-capture."*
- **Pág. 6 (Definition of Done):** *"La escritura de la orden y su fila de outbox
  ocurren en una única escritura transaccional de DynamoDB."*

**Camino recomendado: CDC vía DynamoDB Streams**, que es lo que describe el diseño
detallado, el esquema de tablas y el diagrama de flujo (paso 7: "Lambda disparada
directamente por DynamoDB Streams"). El bullet del DoD parece residuo de una versión
anterior del kata. Confírmalo con el mentor, pero no bloquees el avance por ello:
la Fase 6 es tardía y cambiar de camino después cuesta medio día.

Lo que sí hay que poder **explicar** (el PDF lo pide como objetivo aparte):

El problema: guardar estado y emitir un evento son dos sistemas distintos, y no hay
forma de hacerlos atómicos. Si guardas y mueres antes de publicar, el evento se
perdió; si publicas y falla el guardado, anunciaste algo inexistente. Ningún orden
de las dos operaciones es seguro.

La solución outbox clásica: en una transacción escribes el estado *y* una fila
"publica esto". Un dispatcher aparte lee las pendientes, publica, y las marca.
Como puede morir a medio camino, el dispatcher debe ser idempotente — puede publicar
el mismo evento dos veces.

Lo que hace CDC: la base de datos misma emite el flujo de cambios. El hecho de haber
escrito la orden *ya es* el evento, así que la fila de outbox y la transacción
sobran. La atomicidad la da Dynamo: si el write ocurrió, el evento saldrá.

El coste que CDC no elimina: la entrega sigue siendo **at-least-once**. Streams puede
reentregar un registro, así que el consumidor sigue necesitando ser idempotente.
CDC te quita la tabla de outbox, no el requisito de idempotencia.

## DynamoDB Streams: la configuración que importa

```typescript
const orders = new dynamodb.Table(this, 'Orders', {
  tableName: 'Orders',
  partitionKey: { name: 'orderId', type: dynamodb.AttributeType.STRING },
  billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
  stream: dynamodb.StreamViewType.NEW_IMAGE,   // ← sin esto no hay eventos
  removalPolicy: RemovalPolicy.DESTROY,        // kata: tirar todo al hacer down
});
```

`NEW_IMAGE` entrega el ítem completo tal como quedó tras el cambio. Es lo que pide
el PDF y lo que necesitas: la Lambda construye el evento `OrderPriced` sin volver a
consultar la tabla. (`NEW_AND_OLD_IMAGES` sirve para detectar qué cambió, pero aquí
no hace falta y duplica el tamaño del payload.)

La suscripción de la Lambda:

```typescript
processor.addEventSource(new eventsources.DynamoEventSource(orders, {
  startingPosition: lambda.StartingPosition.LATEST,
  batchSize: 10,
  retryAttempts: 3,
  reportBatchItemFailures: true,   // fallos parciales: no reintenta el lote entero
}));
```

`reportBatchItemFailures` vale la pena mencionarlo en el PR: sin él, un solo registro
fallido hace reintentar los diez, lo que multiplica los duplicados que tu consumidor
idempotente tiene que absorber.

## Idempotencia del consumidor

Con at-least-once, la Lambda puede ver el mismo `orderId` dos veces. Opciones, de
más simple a más robusta:

1. **Clave de partición determinista en Kinesis** — usa `orderId` como partition key.
   No deduplica, pero garantiza orden por pedido dentro del shard, que suele ser lo
   que de verdad importa.
2. **Evento idempotente por diseño** — el payload lleva el estado completo, no un
   delta. Procesarlo dos veces deja el mismo resultado. Es la opción más limpia aquí.
3. **Tabla de deduplicación** — registrar `(orderId, eventVersion)` con condición
   `attribute_not_exists`. Correcto pero es más infraestructura de la que el kata pide.

Para este kata la 2 basta, y explicar por qué basta es mejor que implementar la 3.

## Lambda en Scala: la decisión de runtime

Dos caminos, con un trade-off real:

**a) `feral` (Typelevel), versión 0.3.1** — pensado para escribir Lambdas con
cats-effect. Da un `IOLambda` con manejo de `Resource` correcto entre invocaciones y
eventos tipados. Encaja con el estilo del kata; a cambio es una dependencia más y
menos común en StackOverflow.

Soporta DynamoDB Streams de forma completa: `DynamoDbStreamEvent`, `DynamoDbRecord` y
el ADT de `AttributeValue` vienen en el JAR `feral-lambda` (el artefacto suelto
`feral-lambda-events` está congelado — no lo uses). La forma del handler:

```scala
object StreamProcessor extends IOLambda[DynamoDbStreamEvent, Unit]:
  def handler = Clients.all[IO].map { deps =>   // Resource: se abre una vez, no por invocación
    (event: Invocation[IO, DynamoDbStreamEvent]) =>
      event.event.flatMap(e => Processor.handle[IO](e.records, deps)).as(None)
  }
```

**Usa 0.3.1, no `1.0.0-M4`** — la M4 es de julio de 2022, más vieja que la 0.3.1 pese a
ordenar "por encima", y depende de http4s `1.0.0-M34`. En 0.3.x `LambdaEnv` se renombró
a `Invocation`, así que ejemplos viejos no compilan tal cual.

**b) `RequestHandler` del SDK de Java + puente a `IO`** — la interfaz estándar de AWS,
con `IORuntime` creado una vez fuera del handler:

```scala
class StreamProcessor extends RequestHandler[DynamodbEvent, Unit]:
  // El runtime y los clientes viven fuera del handler para reutilizarse
  // entre invocaciones calientes — crearlos por invocación es el error clásico.
  private val (deps, shutdown) = Clients.all[IO].allocated.unsafeRunSync()(runtime)

  def handleRequest(event: DynamodbEvent, ctx: Context): Unit =
    Processor.handle[IO](event, deps).unsafeRunSync()(runtime)
```

Ojo con el **cold start de la JVM** en cualquiera de los dos: son cientos de ms.
En producción se mitiga con SnapStart o GraalVM native-image; para el kata no importa,
pero mencionarlo en el README demuestra que lo tienes presente.

## fs2 en el procesador

El PDF pide `parEvalMap` con concurrencia acotada, no un bucle con efectos:

```scala
def handle[F[_]: Async](records: List[DynamodbStreamRecord], kinesis: KinesisPublisher[F]): F[Unit] =
  Stream.emits(records)
    .map(toOrderPriced)               // puro: registro del stream → evento de dominio
    .unNone                           // descarta REMOVE y lo que no aplique
    .parEvalMap(4)(kinesis.publish)   // 4 en vuelo, no 500
    .compile
    .drain
```

Por qué acotada: sin límite, un lote grande abre tantas llamadas concurrentes como
registros y te comes el throttling de Kinesis. `parEvalMap(n)` mantiene n en vuelo
**preservando el orden de salida**; si el orden no importa, `parEvalMapUnordered` es
más rápido. Para eventos por pedido el orden sí suele importar.

## Kinesis: publicar

Con `PutRecords` (por lotes) en vez de `PutRecord` reduces llamadas, pero atención:
`PutRecords` es un éxito parcial — la respuesta trae `failedRecordCount` y hay que
inspeccionar registro por registro. Un `PutRecords` cuya respuesta no se revisa es
un descarte silencioso de eventos, y es un hallazgo fácil en code review.

```scala
def publish(events: List[OrderPriced]): F[Unit] =
  for
    resp <- client.putRecords(req(events))
    _    <- Sync[F].raiseError(...).whenA(resp.failedRecordCount > 0)  // o reintentar los fallidos
  yield ()
```

## LocalStack: los límites reales (verificado, julio 2026)

**Ya no existe la "Community edition".** El soporte Community terminó el 23 de marzo
de 2026. Ahora hay una sola imagen que **requiere cuenta y `LOCALSTACK_AUTH_TOKEN`**,
y el tier gratuito se llama **Hobby**. Importante: Hobby es **sólo para uso no
comercial**. Si este kata está ligado a un proceso de contratación de una empresa,
conviene aclarar la licencia antes de empezar — aplica sin importar qué servicios uses.

### Qué está en el tier gratuito (Hobby)

| Servicio | Hobby | Nota |
|---|---|---|
| DynamoDB | ✅ | |
| DynamoDB Streams | ✅ | Sin persistencia en Hobby |
| Kinesis | ✅ | |
| Lambda | ✅ | Marcado "Limited Support" |
| CloudFormation | ✅ | Marcado "Limited Support" |
| IAM | ✅ | CRUD sí; enforcement de políticas es Ultimate |
| **ECS / Fargate** | ❌ | **Base o superior (~39 USD/mes anual)** |

**La integración clave del kata —DynamoDB Streams disparando Lambda (event source
mapping)— funciona en Hobby**, con batch size, filtros y ventanas temporales
implementados. O sea: el flujo central del ejercicio corre gratis.

Varios blogs afirman que ECS está en el tier gratuito. **Están equivocados** — la doc
oficial y la página de precios coinciden en Base+.

### Consecuencia para la Fase 7: Fargate

ECS está bloqueado por completo en Hobby, no parcialmente. Plan B:

- Definir el servicio Fargate en el CDK (se revisa como código, que es lo que pide el PDF).
- Correr la API en `docker-compose` como contenedor normal para el bucle local.
- Documentar la razón en el README.

Cumple el espíritu del requisito —infra propia definida en CDK— sin pagar licencia.

### Consecuencia para el deploy de la Lambda

Esta es la trampa que más cuesta descubrir tarde. De la doc oficial de la integración
con CDK: *"CDK Asset deployment (e.g., Lambda code, S3 content) requires a LocalStack
paid plan."* El publicado de assets usa `AWS::CloudFormation::CustomResource`.

**Traducción: `Code.fromAsset(...)` no funciona en Hobby.** Como tu Lambda se despliega
vía CDK, hay que rodearlo con el bucket de **hot-reload**, que sí está disponible:

```typescript
// En LocalStack Hobby: monta el jar desde disco en vez de subirlo como asset
const code = process.env.LOCALSTACK
  ? lambda.Code.fromBucket(
      s3.Bucket.fromBucketName(this, 'HotReload', 'hot-reload'),
      '/ruta/absoluta/a/lambda/target/scala-3.8.4')   // directorio, no el jar
  : lambda.Code.fromAsset('../lambda/target/scala-3.8.4/lambda-assembly.jar');
```

El bucket `hot-reload` es mágico de LocalStack: monta un directorio local dentro de la
Lambda, y recompilar refresca el código sin re-desplegar. Para el bucle de desarrollo
incluso es más cómodo que el asset real.

### cdklocal

Sigue siendo la vía recomendada oficialmente. Versión actual **3.0.4** (abril 2026),
mantenida activamente. `cdk` + `AWS_ENDPOINT_URL` a mano **no** es alternativa soportada.

```bash
npm install -g aws-cdk-local aws-cdk    # aws-cdk es dependencia manual
cdklocal bootstrap && cdklocal deploy
```

Versiones actuales: `aws-cdk-lib` **2.262.2**, CLI `aws-cdk` **2.1134.0**.

Detalles que muerden:

- Desde `aws-cdk >= 2.177.0`, cdklocal **borra las variables AWS del entorno** (como
  `AWS_PROFILE`) antes de invocar `cdk`, para evitar despliegues accidentales contra
  AWS real. Si necesitas propagar alguna: `AWS_ENVAR_ALLOWLIST=AWS_REGION,AWS_DEFAULT_REGION`.
- **Actualizar stacks es frágil.** La doc lo dice explícitamente: *"es aconsejable
  priorizar re-crear (borrar y re-desplegar) sobre actualizar stacks."* Para el kata,
  que `make down && make up && make deploy` sea el camino normal.
- En macOS con CDK instalado por brew puede salir `MODULE_NOT_FOUND`; se arregla
  apuntando `NODE_PATH` al `node_modules` del CDK.
- Si un deploy se queda colgado sin error claro, busca fallos de `CustomResource` en
  los logs de LocalStack — suele ser el problema de assets de arriba.

## docker-compose y Makefile

```yaml
services:
  localstack:
    image: localstack/localstack:2026.07.1   # pinnea: 'latest' se mueve
    ports:
      - "127.0.0.1:4566:4566"
      - "127.0.0.1:4510-4559:4510-4559"
    environment:
      - LOCALSTACK_AUTH_TOKEN=${LOCALSTACK_AUTH_TOKEN:?falta el token}
      - SERVICES=dynamodb,dynamodbstreams,kinesis,lambda,cloudformation,iam,s3
      - DEBUG=${DEBUG:-0}
    volumes:
      - "./volume:/var/lib/localstack"
      - "/var/run/docker.sock:/var/run/docker.sock"   # necesario para ejecutar Lambdas
      - "./lambda/target:/lambda-code"                # para el bucket hot-reload
```

El montaje del socket de Docker es obligatorio: LocalStack ejecuta cada Lambda en un
contenedor propio, así que necesita hablar con el daemon del host.

El PDF pide exactamente cuatro targets:

```makefile
.PHONY: up deploy test-integration down

up:               ## LocalStack en background
	docker compose up -d
	@./scripts/wait-for-localstack.sh     # sin esto, el deploy corre contra un LocalStack a medio arrancar

deploy:           ## CDK contra LocalStack
	cd cdk && npm ci && npx cdklocal bootstrap && npx cdklocal deploy --require-approval never

test-integration: ## tests de integración (testcontainers levanta lo suyo)
	sbt "IntegrationTest/test"

down:
	docker compose down -v
```

El criterio de aceptación es `make up && make deploy && make test-integration`
limpio **desde un checkout nuevo**. Pruébalo de verdad en un clon limpio antes del PR:
el fallo típico es depender de estado que quedó de una corrida anterior.

El `wait-for-localstack.sh` importa más de lo que parece — LocalStack tarda unos
segundos en estar listo y el `deploy` inmediato falla de forma intermitente, que es
justo el tipo de flakiness que arruina una demo:

```bash
until curl -s http://localhost:4566/_localstack/health | grep -q '"dynamodb": "\(available\|running\)"'; do
  sleep 1
done
```

## Seed de datos para el bucle manual

Las tablas `Customers` y `Coupons` son lookups: sin datos, todo request falla en
validación. Un script de seed con `awslocal` hace la demo posible:

```bash
awslocal dynamodb put-item --table-name Customers \
  --item '{"customerId":{"S":"cust-123"},"tier":{"S":"GOLD"},"createdAt":{"S":"2026-01-01T00:00:00Z"}}'
awslocal dynamodb put-item --table-name Coupons \
  --item '{"couponCode":{"S":"SUMMER10"},"discountPercent":{"N":"10"},"minOrderAmount":{"N":"50"},
           "usageLimit":{"N":"100"},"usageCount":{"N":"0"},"expiresAt":{"S":"2026-12-31T23:59:59Z"},
           "stackableWithTier":{"BOOL":true}}'
```

Y para ver el evento al final de la cadena, que es la demo completa del flujo:

```bash
SHARD=$(awslocal kinesis list-shards --stream-name order-priced-events \
        --query 'Shards[0].ShardId' --output text)
ITER=$(awslocal kinesis get-shard-iterator --stream-name order-priced-events \
       --shard-id $SHARD --shard-iterator-type TRIM_HORIZON --query ShardIterator --output text)
awslocal kinesis get-records --shard-iterator $ITER
```

## Notas de CDK que se revisan

- **Permisos mínimos**: `orders.grantStreamRead(fn)` y `stream.grantWrite(fn)`, no
  `grantFullAccess`. Es lo primero que mira alguien revisando IaC.
- **Nada de nombres hardcodeados** en el código Scala: los nombres de tabla y stream
  llegan por variables de entorno, y el CDK las inyecta (`environment: { ORDERS_TABLE: orders.tableName }`).
  Eso conecta con ciris del otro lado.
- **`removalPolicy: DESTROY`** en el kata, para que `make down` deje limpio.
  En producción sería lo contrario, y vale decirlo en un comentario.
- **Un stack o varios**: para este tamaño, uno solo está bien. No inventes separación
  que no aporta.

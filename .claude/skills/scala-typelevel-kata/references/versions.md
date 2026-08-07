# Versiones y build

> Verificado contra Maven Central el **30 de julio de 2026**. Si pasan meses, revalida
> antes de confiar: este stack se mueve rápido.

## Base

| Componente | Versión | Nota |
|---|---|---|
| Scala | **3.8.4** | Publicada el 5 de junio de 2026. Es línea **Next**, no LTS. |
| JDK | **21** | El PDF lo fija: el cliente corre 21/25, y 21 es el default seguro. |
| sbt | 1.x o 2.x | smithy4s publica plugin para ambos — ver abajo. |

Instalación de Scala vía Coursier: `cs install scala:3.8.4 && cs install scalac:3.8.4`

## Librerías (verificadas)

| Librería | Artefacto | Versión |
|---|---|---|
| cats | `org.typelevel::cats-core` | **2.13.0** |
| cats-effect | `org.typelevel::cats-effect` | **3.7.0** |
| http4s | `org.http4s::http4s-ember-server`, `-ember-client`, `-dsl` | **0.23.36** |
| fs2 | `co.fs2::fs2-core` | **3.13.0** |
| smithy4s | `com.disneystreaming.smithy4s::smithy4s-core`, `-http4s` | **0.19.11** |
| smithy4s plugin (sbt 1.x) | `…:smithy4s-sbt-codegen_2.12_1.0` | **0.19.11** |
| smithy4s plugin (sbt 2.x) | `…:smithy4s-sbt-codegen_sbt2_3` | **0.19.11** |
| chimney | `io.scalaland::chimney` | **1.11.0** |
| ciris | `is.cir::ciris` | **3.15.0** |
| natchez | `org.tpolecat::natchez-core` | **0.3.10** |
| weaver | `org.typelevel::weaver-cats` | **0.13.0** |
| weaver-scalacheck | `org.typelevel::weaver-scalacheck` | **0.13.0** |
| circe | `io.circe::circe-core` | **0.14.16** |
| jsoniter-scala | `com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core` | **2.39.1** |
| testcontainers-scala | `com.dimafeng::testcontainers-scala-localstack` | **0.44.1** |
| ScalaCheck | `org.scalacheck::scalacheck` | **1.19.0** |
| AWS SDK v2 | `software.amazon.awssdk:dynamodb`, `:kinesis` | **2.49.6** |
| aws-lambda-java-core | `com.amazonaws:aws-lambda-java-core` | **1.4.0** |
| aws-lambda-java-events | `com.amazonaws:aws-lambda-java-events` | **3.16.1** |
| feral (opcional) | `org.typelevel::feral-lambda` | **0.3.1** |

## Lo que hay que saber sobre estas versiones

**Ninguna librería está compilada contra Scala 3.8, y está bien así.** Todos los
artefactos `_3` apuntan a **Scala 3.3.x LTS** (cabecera TASTy `28.3`), que 3.8.4
consume por compatibilidad hacia adelante. No busques builds específicos para 3.8:
no existen y no hacen falta.

**Trampa de la stdlib en 3.8.x.** Desde 3.8.0, `org.scala-lang:scala3-library_3` es un
JAR vacío de 9 KB que reenvía a un nuevo `org.scala-lang:scala-library:3.8.4` (sin
sufijo) donde vive la stdlib real. Vía sbt es transparente, pero **rompe reglas de
shading/assembly** que asuman que la stdlib está en `scala3-library_3`. Relevante para
el fat jar de la Lambda: si el merge strategy de assembly falla raro, mira por ahí.

**Cuidado con los "latest" de Maven Central**, que ordenan por string y mienten:
- `cats-effect 3.7-4972921` es un snapshot de CI → usa **3.7.0**.
- `http4s 1.0.0-M47` es milestone → la estable es **0.23.36**, activamente mantenida.
- `chimney 2.0.0-M4` y `circe 0.15.0-M1` son milestones.
- **`feral 1.0.0-M4` es de julio de 2022** — más *vieja* que la 0.3.1 (sept 2024) que
  ordena "por debajo". Depende de http4s `1.0.0-M34`. Usa **0.3.1**.

**smithy4s NO cambió de group id.** Sigue en `com.disneystreaming.smithy4s`;
`software.amazon.smithy4s` y `tech.neandertech` no existen en Maven Central. Lo que
aparece en búsquedas como "Smithy-Java de AWS" es un framework distinto y no aplica.
La serie actual es **0.19.x** (0.19.11 del 17 de julio de 2026), no la 0.18 que sugieren
tutoriales viejos. **sbt 2.x está soportado** vía el artefacto `_sbt2_3`.

**weaver: usa `org.typelevel`, no `com.disneystreaming`.** El group viejo murió en
0.8.4 (enero 2024); el mantenido es `org.typelevel::weaver-cats` **0.13.0** (junio 2026),
repo en `github.com/typelevel/weaver-test`. Sin issues abiertos de Scala 3.7/3.8.
Ojo: **0.13.0 trae cambios rompientes** (eliminó las suites `Mutable*`) que requieren
una migración con scalafix. Adóptala desde el día uno y te ahorras migrar después.

**testcontainers-scala arrastra AWS SDK v1.** El módulo localstack declara
`aws-java-sdk-s3`/`-sqs` **1.11.479** (de 2018, EOL) en scope `provided`, así que no
llega a tu runtime salvo que los añadas. No dejes que confundan tu uso del SDK v2.

**natchez vs otel4s.** natchez 0.3.10 está vigente y el PDF lo pide por nombre, pero
`otel4s` ya llegó a 1.0.1 y es hacia donde va el ecosistema Typelevel. Para el kata
natchez es correcto — sólo ten lista la justificación si el mentor pregunta.

## Lambda: feral o SDK de Java

**feral 0.3.1** soporta DynamoDB Streams de forma completa: trae `DynamoDbStreamEvent`,
`DynamoDbRecord` y el ADT de `AttributeValue` dentro del propio JAR `feral-lambda`
(el artefacto suelto `feral-lambda-events` está congelado en un snapshot — ignóralo).
La forma del handler es `IOLambda[DynamoDbStreamEvent, Unit]`; en 0.3.x se renombró
`LambdaEnv` a `Invocation`.

El repo tiene commits recientes (julio 2026) pero lleva tiempo sin release, y
`tlBaseVersion` sigue en `"0.3"` — así que 1.0.0 no es inminente. Está mantenido, no
abandonado. Si prefieres evitar la dependencia, el `RequestHandler` del SDK de Java
funciona igual (ver `references/aws-local.md`).

## Layout de módulos

La separación física es lo que hace verificable la regla "el núcleo puro no importa IO":

```
scala-pricing-kata/
├── build.sbt
├── project/plugins.sbt
├── domain/src/main/scala/          ← puro: modelos, validación, pricing
├── service/
│   ├── src/main/smithy/            ← el modelo IDL (primer artefacto que escribes)
│   └── src/main/scala/             ← smithy4s + http4s + repos + composition root
├── lambda/src/main/scala/          ← procesador de Streams → Kinesis
├── cdk/                            ← infra (Node/TypeScript o JVM, según decidan)
├── docker-compose.yml
└── Makefile
```

## build.sbt

```scala
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.kata"

val catsVersion         = "2.13.0"
val catsEffectVersion   = "3.7.0"
val http4sVersion       = "0.23.36"
val fs2Version          = "3.13.0"
val chimneyVersion      = "1.11.0"
val cirisVersion        = "3.15.0"
val natchezVersion      = "0.3.10"
val weaverVersion       = "0.13.0"
val tcVersion           = "0.44.1"
val awsSdkVersion       = "2.49.6"
val lambdaCoreVersion   = "1.4.0"
val lambdaEventsVersion = "3.16.1"
val wiremockVersion     = "3.13.1"  // org.wiremock:wiremock — las 4.0.0-beta.x no son estables

// Flags que valen la pena en un proyecto que se revisa como entrevista:
// -Wunused delata imports y variables muertas; -source:future adopta la semántica
// nueva de Scala 3. No pongas -Xfatal-warnings hasta el final, o pelearás con el
// compilador mientras exploras.
ThisBuild / scalacOptions ++= Seq(
  "-deprecation", "-feature", "-unchecked",
  "-Wunused:all", "-source:future"
)

lazy val domain = project
  .in(file("domain"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      // Nada más. Sin cats-effect, sin http4s, sin AWS.
      // Esta lista corta ES la regla 1 del DoD, hecha estructura.
      "org.typelevel" %% "weaver-cats"       % weaverVersion % Test,
      "org.typelevel" %% "weaver-scalacheck" % weaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val service = project
  .in(file("service"))
  .dependsOn(domain)
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion.value,
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.typelevel" %% "cats-effect"         % catsEffectVersion,
      "io.scalaland"  %% "chimney"             % chimneyVersion,
      "is.cir"        %% "ciris"               % cirisVersion,
      "org.tpolecat"  %% "natchez-core"        % natchezVersion,
      "software.amazon.awssdk" % "dynamodb"    % awsSdkVersion,
      // tests
      "org.typelevel"  %% "weaver-cats"        % weaverVersion % Test,
      "org.typelevel"  %% "cats-effect-testkit" % catsEffectVersion % Test,  // TestControl
      "com.dimafeng"   %% "testcontainers-scala-localstack" % tcVersion % Test,
      "org.wiremock"   %  "wiremock"           % wiremockVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val lambda = project
  .in(file("lambda"))
  .dependsOn(domain)
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2"        %% "fs2-core"   % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "software.amazon.awssdk" % "kinesis"  % awsSdkVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "com.amazonaws" % "aws-lambda-java-core"   % lambdaCoreVersion,
      "com.amazonaws" % "aws-lambda-java-events" % lambdaEventsVersion
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )

lazy val root = project.in(file("."))
  .aggregate(domain, service, lambda)
```

`project/plugins.sbt`:

```scala
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.11")
addSbtPlugin("com.eed3si9n"                 % "sbt-assembly"         % "2.3.1")  // verificar
```

`sbt-assembly` hace falta para empaquetar la Lambda en un fat jar. Recuerda la trampa
de la stdlib en Scala 3.8: si el merge strategy se comporta raro, es porque
`scala3-library_3` ya no contiene la stdlib.

## Verificar la separación de capas

El DoD exige que el núcleo puro no toque efectos. Además de la lista corta de
dependencias, un chequeo rápido antes del PR:

```bash
grep -rn "cats.effect\|http4s\|awssdk\|smithy4s" domain/src/main && echo "FALLO" || echo "limpio"
```

Se puede automatizar en CI. Es el bullet más fácil de verificar de todo el DoD, así
que no conviene fallarlo.

## Config con ciris

El PDF prohíbe `sys.env(...)` pelado. La gracia de ciris es que la config falla al
arrancar con un mensaje claro, no a mitad de un request con un `NoSuchElementException`:

```scala
final case class AppConfig(aws: AwsConfig, server: ServerConfig, partner: PartnerConfig)

object AppConfig:
  def load[F[_]: Async]: F[AppConfig] =
    (
      env("ORDERS_TABLE").as[String].default("Orders"),
      env("KINESIS_STREAM").as[String].default("order-priced-events"),
      env("AWS_REGION").as[String].default("us-east-1"),
      env("LOCALSTACK_ENDPOINT").as[Option[String]],   // sólo en local
      env("PARTNER_TIMEOUT").as[FiniteDuration].default(2.seconds)
    ).parMapN(...).load[F]
```

`parMapN` acumula **todos** los errores de config: si faltan tres variables, las
reporta las tres de una vez en vez de una por arranque. Es el mismo principio de
acumulación que `Validated` en la validación del dominio.

Los nombres de tabla y stream los inyecta el CDK como variables de entorno — así el
mismo binario corre en LocalStack y en AWS sin recompilar.

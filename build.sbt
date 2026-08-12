ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.kata"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val catsVersion         = "2.13.0"
val catsEffectVersion   = "3.7.0"
val http4sVersion       = "0.23.36"
val fs2Version          = "3.13.0"
val chimneyVersion      = "1.11.0"
val cirisVersion        = "3.15.0"
val natchezVersion      = "0.3.10"
val log4catsVersion     = "2.8.0"
val slf4jVersion        = "2.0.18"
val weaverVersion       = "0.13.0"
val tcVersion           = "0.44.1"
val awsSdkVersion       = "2.49.6"
val lambdaCoreVersion   = "1.4.0"
val lambdaEventsVersion = "3.16.1"
val wiremockVersion     = "3.13.1"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all"
)

// `-source:future` convierte `implicit val` en error. La codegen de smithy4s 0.19.11
// todavía emite `implicit val schema = ...`, así que sólo puede activarse en los
// módulos cuyas fuentes escribimos nosotros. `service` compila código generado.
val futureSource = Seq("-source:future")

lazy val weaverDeps = Seq(
  "org.typelevel" %% "weaver-cats"       % weaverVersion % Test,
  "org.typelevel" %% "weaver-scalacheck" % weaverVersion % Test
)

// El núcleo puro. Su lista de dependencias ES la regla 1 del DoD hecha estructura:
// sin cats-effect, sin http4s y sin el SDK de AWS en el classpath, no se puede
// violar por accidente.
lazy val domain = project
  .in(file("domain"))
  .settings(
    name := "pricing-domain",
    scalacOptions ++= futureSource,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val service = project
  .in(file("service"))
  .dependsOn(domain)
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "pricing-service",
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s"     % smithy4sVersion.value,
      "org.http4s"                   %% "http4s-ember-server" % http4sVersion,
      "org.http4s"                   %% "http4s-ember-client" % http4sVersion,
      "org.typelevel"                %% "cats-effect"         % catsEffectVersion,
      "io.scalaland"                 %% "chimney"             % chimneyVersion,
      "is.cir"                       %% "ciris"               % cirisVersion,
      // `natchez-core` is the abstract `Trace[F]`/`Span[F]` API. `natchez-log` is the
      // local entrypoint that prints spans to the console — enough to demonstrate
      // propagation without running a collector; swapping it for Datadog is a one-line
      // change in `Main` because nothing else names a backend.
      "org.tpolecat"                 %% "natchez-core"        % natchezVersion,
      "org.tpolecat"                 %% "natchez-log"         % natchezVersion,
      // `natchez-log` writes spans through log4cats; slf4j-simple is the backend that
      // actually prints them, and is only needed at runtime.
      "org.typelevel"                %% "log4cats-slf4j"      % log4catsVersion,
      "org.slf4j"                     % "slf4j-simple"        % slf4jVersion % Runtime,
      "software.amazon.awssdk"        % "dynamodb"            % awsSdkVersion,
      "org.typelevel"                %% "cats-effect-testkit" % catsEffectVersion % Test,
      "com.dimafeng"                 %% "testcontainers-scala-localstack" % tcVersion % Test,
      "org.wiremock"                  % "wiremock"            % wiremockVersion   % Test
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    // A fat jar so the Docker runtime stage needs only a JRE. The merge strategy discards
    // META-INF signatures, which otherwise make the JVM reject the combined jar.
    assembly / assemblyJarName := "pricing-service-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )

lazy val lambda = project
  .in(file("lambda"))
  .dependsOn(domain)
  .settings(
    name := "stream-processor",
    scalacOptions ++= futureSource,
    libraryDependencies ++= Seq(
      "co.fs2"                       %% "fs2-core"               % fs2Version,
      "org.typelevel"                %% "cats-effect"            % catsEffectVersion,
      "software.amazon.awssdk"        % "kinesis"                % awsSdkVersion,
      "software.amazon.awssdk"        % "dynamodb"               % awsSdkVersion,
      "com.amazonaws"                 % "aws-lambda-java-core"   % lambdaCoreVersion,
      "com.amazonaws"                 % "aws-lambda-java-events" % lambdaEventsVersion
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(domain, service, lambda)
  .settings(
    name           := "scala-pricing-kata",
    publish / skip := true
  )

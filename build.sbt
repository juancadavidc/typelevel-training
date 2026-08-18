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

// LocalStack's `hot-reload` bucket mounts a directory as the function's `/var/task`, and
// the Java runtime only puts `/var/task` itself and `/var/task/lib/*.jar` on the
// classpath. Handing it `target/scala-3.8.4` — where sbt keeps `classes/`, three
// different jars and its own zinc bookkeeping — deploys cleanly and then dies with
// `ClassNotFoundException` on the first invocation, because the assembly sits in a
// directory nobody reads. This task builds the one layout that works.
lazy val hotReloadStage =
  taskKey[File]("Assemble the Lambda and stage it in the layout LocalStack's hot-reload bucket needs")

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
      // WireMock stubs the loyalty partner in `LoyaltyClientSuite`. Testcontainers is not
      // here: the integration suite that needs it lives in the `it` module.
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
  .dependsOn(domain % "compile->compile;test->test")
  .settings(
    name := "stream-processor",
    scalacOptions ++= futureSource,
    libraryDependencies ++= Seq(
      "co.fs2"                       %% "fs2-core"               % fs2Version,
      "org.typelevel"                %% "cats-effect"            % catsEffectVersion,
      "is.cir"                       %% "ciris"                  % cirisVersion,
      "software.amazon.awssdk"        % "kinesis"                % awsSdkVersion,
      "software.amazon.awssdk"        % "dynamodb"               % awsSdkVersion,
      "com.amazonaws"                 % "aws-lambda-java-core"   % lambdaCoreVersion,
      "com.amazonaws"                 % "aws-lambda-java-events" % lambdaEventsVersion
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    // Pinned because `cdk/bin/pricing.ts` names this file for the non-local deploy path.
    // Without it sbt-assembly appends the version and CDK cannot find the artifact.
    assembly / assemblyJarName := "stream-processor-assembly.jar",
    assembly / assemblyMergeStrategy := {
      // `META-INF/services/*` must be concatenated, and this case must come first.
      //
      // The AWS SDK v2 finds its HTTP transport through `ServiceLoader`: `netty-nio-client`
      // announces itself in `META-INF/services/…SdkAsyncHttpService`. Discarding all of
      // `META-INF` deletes that index while leaving the netty classes in the jar, so the
      // SDK reports «Unable to load an HTTP implementation from any provider in the chain»
      // about an implementation it is carrying. Concatenating rather than taking the first
      // matters too — several dependencies register providers under the same file name, and
      // `first` would silently drop all but one.
      //
      // No unit test can catch this: the suite runs against sbt's classpath, where every
      // dependency keeps its own `META-INF`. Only the assembled artifact is broken, which
      // is why the integration test has to exercise the deployed Lambda.
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    },
    hotReloadStage := {
      val jar   = assembly.value
      val stage = target.value / "hot-reload"
      val lib   = stage / "lib"
      IO.delete(stage)
      IO.createDirectory(lib)
      IO.copyFile(jar, lib / jar.getName)
      streams.value.log.info(s"staged ${jar.getName} for the hot-reload bucket at $stage")
      stage
    }
  )

// The integration suite. Deliberately **not** aggregated by `root`, which is what keeps
// `sbt test` free of Docker — `make test` promises "no Docker, no LocalStack" and that
// promise is worth more than literal compliance with the brief's "via the normal test
// task". `make test-integration` runs `sbt it/test`, which is one line in CI.
//
// `test->compile`, not `compile->compile`: this module has no `src/main` at all, so the
// `service`↔`lambda` edge exists *only* on the test classpath. That answers the question
// this layout invites — why do the producer and the consumer share a classpath when
// production deploys them as two separate artifacts? Because nothing in `compile` scope
// can reach across: there is no compile scope here to reach with.
//
// `domain` is deliberately absent from this list even though the suite uses its types:
// they arrive transitively through `service` and `lambda`, which both depend on it. The
// design sketched a `domain % "test->test"` edge for the ScalaCheck generators, but this
// suite asserts the brief's worked example — fixed, published numbers — rather than
// generated data, so that edge would declare a coupling that does not exist.
lazy val it = project
  .in(file("it"))
  .dependsOn(service % "test->compile", lambda % "test->compile")
  .settings(
    name           := "pricing-integration",
    publish / skip := true,
    scalacOptions ++= futureSource,
    libraryDependencies ++= Seq(
      "com.dimafeng"  %% "testcontainers-scala-localstack" % tcVersion       % Test,
      "org.wiremock"   % "wiremock"                        % wiremockVersion % Test,
      "org.slf4j"      % "slf4j-simple"                    % slf4jVersion    % Test
    ) ++ weaverDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
    // Note: the suite runs its tests one at a time, but `Test / parallelExecution` is not
    // what does it — that setting governs parallelism *between* suites, and weaver
    // parallelises *within* one. The knob is `maxParallelism` on the suite itself.
  )

lazy val root = project
  .in(file("."))
  .aggregate(domain, service, lambda)
  .settings(
    name           := "scala-pricing-kata",
    publish / skip := true
  )

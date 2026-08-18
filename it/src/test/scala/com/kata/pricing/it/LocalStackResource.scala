package com.kata.pricing.it

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.LocalStackContainer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.{
  CreateStreamRequest,
  DescribeStreamRequest as KinesisDescribeStreamRequest,
  StreamStatus
}

import java.net.URI
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Everything the integration suite needs from the outside world, as a single `Resource`.
  *
  * DoD #5 applied to test code as well as production code: the container, three AWS
  * clients and the WireMock server all release in reverse order, including on
  * cancellation — which is what happens when a test times out and weaver tears the suite
  * down mid-flight.
  *
  * One container for the whole suite, not one per test. A container per test would
  * quadruple a ~15 second startup and buy no isolation that data isolation does not
  * already give: each test derives its own `orderId` from its own name, so the tests share
  * a table and a stream without ever seeing each other's rows.
  */
final case class TestStack(
    dynamo: DynamoDbAsyncClient,
    streams: DynamoDbStreamsAsyncClient,
    kinesis: KinesisAsyncClient,
    wireMock: WireMockServer,
    endpoint: URI,
    region: Region
):
  def loyaltyBaseUri: String = s"http://localhost:${wireMock.port()}"

object LocalStackResource:

  val customersTable = "Customers"
  val couponsTable   = "Coupons"
  val ordersTable    = "Orders"
  val kinesisStream  = "order-priced-events"

  /** Pinned to the same image `docker-compose.yml` runs, rather than the version
    * testcontainers-scala happens to default to (4.0.3 in 0.44.1).
    *
    * Not cosmetic: LocalStack retired its Community edition in March 2026, and the two
    * images differ in more than a tag. Testing against a different LocalStack build than
    * the manual loop deploys to would mean the suite and `make deploy` can disagree about
    * what the platform does — precisely the discrepancy an integration suite exists to
    * rule out.
    */
  private val image = DockerImageName
    .parse("localstack/localstack:2026.07.3")
    .asCompatibleSubstituteFor("localstack/localstack")

  def resource: Resource[IO, TestStack] =
    for
      container <- containerResource
      endpoint   = container.container.getEndpoint
      region     = Region.of(container.container.getRegion)
      credentials = staticCredentials(container)
      dynamo    <- client(
                     DynamoDbAsyncClient
                       .builder()
                       .endpointOverride(endpoint)
                       .region(region)
                       .credentialsProvider(credentials)
                       .build()
                   )
      streams   <- client(
                     DynamoDbStreamsAsyncClient
                       .builder()
                       .endpointOverride(endpoint)
                       .region(region)
                       .credentialsProvider(credentials)
                       .build()
                   )
      kinesis   <- client(
                     KinesisAsyncClient
                       .builder()
                       .endpointOverride(endpoint)
                       .region(region)
                       .credentialsProvider(credentials)
                       .build()
                   )
      wireMock  <- wireMockResource
      _         <- Resource.eval(requireAmbientCredentials)
      _         <- Resource.eval(createInfrastructure(dynamo, kinesis))
      _         <- Resource.eval(seed(dynamo))
    yield TestStack(dynamo, streams, kinesis, wireMock, endpoint, region)

  /** The container needs `LOCALSTACK_AUTH_TOKEN` for the same reason `docker-compose.yml`
    * does — since March 2026 even the free Hobby tier requires an account. Testcontainers
    * does not read `.env`, so the token is taken from the environment and the failure is
    * made explicit here: without it the container starts and then fails every API call
    * with a licensing error, which is a confusing way to learn the token is missing.
    */
  private def containerResource: Resource[IO, LocalStackContainer] =
    Resource.eval(authToken).flatMap { token =>
      Resource.make(
        IO.blocking {
          val container = LocalStackContainer(
            dockerImageName = image,
            services = Seq(Service.DYNAMODB, Service.KINESIS)
          )
          container.container.withEnv("LOCALSTACK_AUTH_TOKEN", token)
          container.container.withEnv("SERVICES", "dynamodb,dynamodbstreams,kinesis")
          container.start()
          container
        }
      )(container => IO.blocking(container.stop()))
    }

  private def authToken: IO[String] =
    IO(sys.env.get("LOCALSTACK_AUTH_TOKEN").filter(_.nonEmpty))
      .flatMap {
        case Some(token) => IO.pure(token)
        case None        =>
          IO.raiseError(
            new IllegalStateException(
              "LOCALSTACK_AUTH_TOKEN is not set. LocalStack requires it since March 2026, " +
                "including on the free Hobby tier. `make test-integration` exports it from " +
                ".env; running `sbt it/test` directly needs it in the environment."
            )
          )
      }

  /** The suite's own clients get credentials passed explicitly (see `staticCredentials`),
    * but `KinesisPublisherLive` is *production* code: it builds its client the way the
    * deployed Lambda does, from the default provider chain. Under LocalStack the Makefile
    * supplies `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`; in a real deployment the
    * execution role does.
    *
    * Checked here, at startup, rather than left to fail later, because of how it fails
    * later: `StreamProcessor` catches publish errors by design (a failed record is
    * reported to Lambda, not thrown), so a missing credential surfaces as an empty Kinesis
    * stream and a 60-second timeout with no mention of credentials anywhere. That is a
    * genuinely misleading failure, and it cost a debugging cycle before this check
    * existed.
    *
    * Deliberately *not* fixed by setting the properties from inside the suite: that would
    * paper over the environment contract this suite exists to verify. If the process has
    * no AWS credentials, neither would the Lambda.
    */
  private def requireAmbientCredentials: IO[Unit] =
    IO {
      List("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY").filter { name =>
        sys.env.get(name).forall(_.isEmpty) && sys.props.get(toSystemProperty(name)).forall(_.isEmpty)
      }
    }.flatMap {
      case Nil     => IO.unit
      case missing =>
        IO.raiseError(
          new IllegalStateException(
            s"${missing.mkString(" and ")} not set. `KinesisPublisherLive` resolves " +
              "credentials from the default AWS chain, exactly as the deployed Lambda does, " +
              "so without them every publish fails and the Kinesis assertions time out " +
              "without naming the cause. `make test-integration` exports them; running " +
              "`sbt it/test` directly needs them in the environment (any value works — " +
              "LocalStack does not check them)."
          )
        )
    }

  private def toSystemProperty(envName: String): String =
    envName match
      case "AWS_ACCESS_KEY_ID"     => "aws.accessKeyId"
      case "AWS_SECRET_ACCESS_KEY" => "aws.secretAccessKey"
      case other                   => other

  /** LocalStack accepts any credentials but the SDK refuses to build a client without
    * them, which is why they are supplied explicitly rather than left to the default
    * provider chain — a developer with no `~/.aws/credentials` would otherwise see the
    * suite fail for a reason unrelated to the code.
    */
  private def staticCredentials(
      container: LocalStackContainer
  ): StaticCredentialsProvider =
    StaticCredentialsProvider.create(
      AwsBasicCredentials.create(
        container.container.getAccessKey,
        container.container.getSecretKey
      )
    )

  /** Every AWS client as a `Resource` (DoD #5), in the tests as much as in `Main`. Each
    * owns a connection pool and an event-loop group; `Resource` makes closing them a
    * consequence of opening them rather than something the suite has to remember. */
  private def client[C <: AutoCloseable](acquire: => C): Resource[IO, C] =
    Resource.fromAutoCloseable(IO.blocking(acquire))

  /** The partner stub, reusing the same mappings `docker-compose.yml` mounts, so the
    * suite and the manual loop agree about what the partner returns. */
  private def wireMockResource: Resource[IO, WireMockServer] =
    Resource.make(
      IO.blocking {
        val server = new WireMockServer(
          WireMockConfiguration
            .options()
            .dynamicPort()
            .usingFilesUnderDirectory("local/wiremock")
        )
        server.start()
        server
      }
    )(server => IO.blocking(server.stop()))

  /** Infrastructure created through the SDK rather than by CDK.
    *
    * That follows from autonomy rather than preference: a container started by
    * testcontainers has no stacks deployed into it, and deploying CDK per test run would
    * make the suite depend on `make deploy` having succeeded first. The cost is stated in
    * the design doc as a known limit — the CDK definitions themselves are validated by
    * `make deploy`, not here.
    *
    * `StreamSpecification` with NEW_IMAGE is the part that matters: without it the table
    * accepts writes and produces no stream at all, and every stream assertion would time
    * out for a reason that looks like eventual consistency.
    */
  private def createInfrastructure(
      dynamo: DynamoDbAsyncClient,
      kinesis: KinesisAsyncClient
  ): IO[Unit] =
    for
      _ <- createTable(dynamo, customersTable, "customerId", streamed = false)
      _ <- createTable(dynamo, couponsTable, "couponCode", streamed = false)
      _ <- createTable(dynamo, ordersTable, "orderId", streamed = true)
      _ <- createKinesisStream(kinesis)
    yield ()

  private def createTable(
      dynamo: DynamoDbAsyncClient,
      table: String,
      key: String,
      streamed: Boolean
  ): IO[Unit] =
    val builder = CreateTableRequest
      .builder()
      .tableName(table)
      .keySchema(KeySchemaElement.builder().attributeName(key).keyType(KeyType.HASH).build())
      .attributeDefinitions(
        AttributeDefinition
          .builder()
          .attributeName(key)
          .attributeType(ScalarAttributeType.S)
          .build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)

    val request =
      if streamed then
        builder
          .streamSpecification(
            StreamSpecification
              .builder()
              .streamEnabled(true)
              .streamViewType(StreamViewType.NEW_IMAGE)
              .build()
          )
          .build()
      else builder.build()

    IO.fromCompletableFuture(IO(dynamo.createTable(request))).void

  private def createKinesisStream(kinesis: KinesisAsyncClient): IO[Unit] =
    val request = CreateStreamRequest.builder().streamName(kinesisStream).shardCount(1).build()
    IO.fromCompletableFuture(IO(kinesis.createStream(request))).void *> awaitStreamActive(kinesis)

  /** Bounded polling, never a constant sleep — the rule that keeps this suite from
    * rotting. A `sleep(2.seconds)` is fast on a laptop and flaky in CI; this returns as
    * soon as the stream is ACTIVE and fails only when it genuinely never became so.
    */
  private def awaitStreamActive(kinesis: KinesisAsyncClient): IO[Unit] =
    Polling.until(30.seconds, "kinesis stream to become ACTIVE") {
      IO.fromCompletableFuture(
        IO(
          kinesis.describeStream(
            KinesisDescribeStreamRequest.builder().streamName(kinesisStream).build()
          )
        )
      ).map(response =>
        Option.when(response.streamDescription().streamStatus() == StreamStatus.ACTIVE)(())
      ).handleError(_ => None)
    }.void

  /** Exactly the brief's example data, matching `local/seed.sh`.
    *
    * The suite seeds through the SDK rather than shelling out to that script for the same
    * reason it creates its own tables: it must run against its own container without any
    * `make` target having been run first.
    */
  private def seed(dynamo: DynamoDbAsyncClient): IO[Unit] =
    val customers = List(
      Map(
        "customerId" -> str("cust-123"),
        "tier"       -> str("GOLD"),
        "name"       -> str("Ada Lovelace"),
        "createdAt"  -> str("2026-01-15T09:00:00Z")
      ),
      Map(
        "customerId" -> str("cust-456"),
        "tier"       -> str("BASIC"),
        "createdAt"  -> str("2026-03-02T11:30:00Z")
      )
    )

    val coupons = List(
      Map(
        "couponCode"         -> str("SUMMER10"),
        "discountPercent"    -> num("10"),
        "minOrderAmount"     -> num("20.00"),
        "usageLimit"         -> num("100"),
        "usageCount"         -> num("0"),
        "expiresAt"          -> str("2027-06-30T23:59:59Z"),
        "stackableWithTiers" -> AttributeValue.fromL(
          List(str("SILVER"), str("GOLD")).asJava
        )
      ),
      Map(
        "couponCode"         -> str("EXPIRED5"),
        "discountPercent"    -> num("5"),
        "minOrderAmount"     -> num("10.00"),
        "usageLimit"         -> num("50"),
        "usageCount"         -> num("0"),
        "expiresAt"          -> str("2026-06-30T00:00:00Z"),
        "stackableWithTiers" -> AttributeValue.fromL(List(str("GOLD")).asJava)
      )
    )

    customers.traverse_(putItem(dynamo, customersTable, _)) *>
      coupons.traverse_(putItem(dynamo, couponsTable, _))

  private def putItem(
      dynamo: DynamoDbAsyncClient,
      table: String,
      item: Map[String, AttributeValue]
  ): IO[Unit] =
    val request = PutItemRequest.builder().tableName(table).item(item.asJava).build()
    IO.fromCompletableFuture(IO(dynamo.putItem(request))).void

  private def str(value: String): AttributeValue = AttributeValue.fromS(value)
  private def num(value: String): AttributeValue = AttributeValue.fromN(value)

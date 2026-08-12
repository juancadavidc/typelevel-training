import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as kinesis from 'aws-cdk-lib/aws-kinesis';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import { DynamoEventSource } from 'aws-cdk-lib/aws-lambda-event-sources';
import { Construct } from 'constructs';

export interface ComputeStackProps extends cdk.StackProps {
  readonly customersTable: dynamodb.Table;
  readonly couponsTable: dynamodb.Table;
  readonly ordersTable: dynamodb.Table;
  readonly orderPricedStream: kinesis.Stream;
  /**
   * Absolute path to the Lambda's assembled jar. Under LocalStack this is mounted
   * directly rather than uploaded — see `lambdaCode` below.
   */
  readonly lambdaArtifactPath: string;
  /**
   * LocalStack's free tier does not include ECS/Fargate or CDK asset publishing, so both
   * are defined but not deployed locally. The brief asks for the Fargate service in CDK
   * and reviews the CDK as a first-class deliverable, so the code stays.
   */
  readonly localMode: boolean;
}

/**
 * The compute half: the Stream Processor Lambda and the Fargate service that runs the API.
 */
export class ComputeStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    const processor = new lambda.Function(this, 'StreamProcessor', {
      functionName: 'stream-processor',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.kata.pricing.lambda.StreamProcessorHandler::handleRequest',
      code: this.lambdaCode(props),
      memorySize: 512,
      // A JVM Lambda pays a cold start measured in seconds, not milliseconds. The default
      // 3s timeout would fail the first invocation on a cold container and look like a
      // code bug.
      timeout: cdk.Duration.seconds(60),
      // `logGroup` rather than the deprecated `logRetention`, which provisions a custom
      // resource Lambda just to set a retention policy — extra moving parts LocalStack
      // would also have to run.
      logGroup: new logs.LogGroup(this, 'StreamProcessorLogs', {
        retention: logs.RetentionDays.ONE_DAY,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
      }),
      environment: {
        KINESIS_STREAM_NAME: props.orderPricedStream.streamName,
        // Present only under LocalStack; the SDK resolves the real endpoint otherwise.
        ...(props.localMode ? { AWS_ENDPOINT_URL: 'http://localhost.localstack.cloud:4566' } : {}),
      },
    });

    props.orderPricedStream.grantWrite(processor);

    /**
     * The Lambda is triggered by the table's stream directly — no queue, no outbox
     * dispatcher polling for pending rows.
     *
     * `startingPosition: LATEST` rather than TRIM_HORIZON: on a redeploy we want new
     * orders, not a replay of everything the table ever saw. `retryAttempts` is bounded
     * because the stream blocks on a failing batch — an unbounded retry on a poison
     * record stalls every later order behind it.
     */
    processor.addEventSource(
      new DynamoEventSource(props.ordersTable, {
        startingPosition: lambda.StartingPosition.LATEST,
        batchSize: 10,
        retryAttempts: 3,
        reportBatchItemFailures: true,
      }),
    );

    if (!props.localMode) {
      this.addFargateService(props);
    }

    new cdk.CfnOutput(this, 'ProcessorFunctionName', { value: processor.functionName });
  }

  /**
   * How the Lambda's code reaches LocalStack.
   *
   * `lambda.Code.fromAsset` is the normal answer, and it does not work here: publishing
   * CDK assets requires a paid LocalStack tier. The free tier offers the `hot-reload`
   * bucket instead — a magic bucket name that makes LocalStack mount a *local directory*
   * as the function's code rather than fetching an uploaded zip.
   *
   * This is the single most expensive trap in the exercise: everything synthesises and
   * deploys cleanly with `fromAsset` right up until the function is invoked, and the
   * failure then looks like a packaging bug.
   */
  private lambdaCode(props: ComputeStackProps): lambda.Code {
    if (!props.localMode) {
      return lambda.Code.fromAsset(props.lambdaArtifactPath);
    }

    return lambda.Code.fromBucket(
      cdk.aws_s3.Bucket.fromBucketName(this, 'HotReload', 'hot-reload'),
      props.lambdaArtifactPath,
    );
  }

  /**
   * The API on ECS Fargate, as the brief specifies.
   *
   * Never deployed against the free LocalStack tier, where ECS is a paid feature. It is
   * written out in full anyway because the brief treats the CDK app as a reviewed
   * deliverable — the code is the point, not just the running container. Locally the same
   * image runs under docker-compose against LocalStack, which exercises the identical
   * configuration path.
   */
  private addFargateService(props: ComputeStackProps): void {
    const vpc = new ec2.Vpc(this, 'PricingVpc', { maxAzs: 2, natGateways: 1 });
    const cluster = new ecs.Cluster(this, 'PricingCluster', { vpc });

    const task = new ecs.FargateTaskDefinition(this, 'PricingTask', {
      memoryLimitMiB: 1024,
      cpu: 512,
    });

    task.addContainer('pricing-api', {
      image: ecs.ContainerImage.fromAsset('..', { file: 'Dockerfile' }),
      portMappings: [{ containerPort: 8080 }],
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'pricing',
        logRetention: logs.RetentionDays.ONE_WEEK,
      }),
      environment: {
        CUSTOMERS_TABLE: props.customersTable.tableName,
        COUPONS_TABLE: props.couponsTable.tableName,
        ORDERS_TABLE: props.ordersTable.tableName,
      },
    });

    // Least privilege, stated as data access rather than hand-written IAM policies: the
    // API reads customers and coupons and writes orders, and nothing else.
    props.customersTable.grantReadData(task.taskRole);
    props.couponsTable.grantReadData(task.taskRole);
    props.ordersTable.grantWriteData(task.taskRole);

    new ecs.FargateService(this, 'PricingService', {
      cluster,
      taskDefinition: task,
      desiredCount: 1,
      assignPublicIp: true,
    });
  }
}

import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as kinesis from 'aws-cdk-lib/aws-kinesis';
import { Construct } from 'constructs';

/**
 * Tables and the event stream: everything the pricing flow reads, writes, or emits to.
 *
 * Split from the compute stack because these outlive it. A redeploy of the service or the
 * Lambda must not risk the data, and `RemovalPolicy.DESTROY` below is safe only because
 * this is a local, disposable environment — in a real account it would be `RETAIN`.
 */
export class DataStack extends cdk.Stack {
  readonly customersTable: dynamodb.Table;
  readonly couponsTable: dynamodb.Table;
  readonly ordersTable: dynamodb.Table;
  readonly orderPricedStream: kinesis.Stream;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Customers and Coupons are plain lookup tables, deliberately not folded into the
    // single-table design: they are read by key and never queried together with orders.
    this.customersTable = new dynamodb.Table(this, 'Customers', {
      tableName: 'Customers',
      partitionKey: { name: 'customerId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    this.couponsTable = new dynamodb.Table(this, 'Coupons', {
      tableName: 'Coupons',
      partitionKey: { name: 'couponCode', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    /**
     * The Orders table, and the one line that makes the whole event flow work:
     * `stream: NEW_IMAGE`.
     *
     * This is what replaces an outbox table. The brief's Definition of Done asks for "the
     * priced-order write and its outbox row in one transactional write", but its own data
     * model says there is no outbox table and no TransactWriteItems — the two contradict
     * each other. Change-data-capture resolves it: with a stream on the table, the write
     * *is* the event, atomically, because there is only one write. An outbox row would be
     * a second thing to keep in step for no benefit.
     *
     * What CDC does not remove is the need for idempotency downstream: DynamoDB Streams
     * delivers at-least-once, so the Lambda must tolerate seeing a record twice.
     */
    this.ordersTable = new dynamodb.Table(this, 'Orders', {
      tableName: 'Orders',
      partitionKey: { name: 'orderId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      stream: dynamodb.StreamViewType.NEW_IMAGE,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    this.orderPricedStream = new kinesis.Stream(this, 'OrderPricedEvents', {
      streamName: 'order-priced-events',
      shardCount: 1,
      retentionPeriod: cdk.Duration.hours(24),
    });

    new cdk.CfnOutput(this, 'OrdersStreamArn', {
      value: this.ordersTable.tableStreamArn ?? 'none',
      description: 'The DynamoDB stream the processor Lambda subscribes to',
    });
  }
}

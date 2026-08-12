#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import * as path from 'path';
import { DataStack } from '../lib/data-stack';
import { ComputeStack } from '../lib/compute-stack';

const app = new cdk.App();

/**
 * `localMode` drives the two places where the free LocalStack tier differs from a real
 * account: ECS/Fargate is not deployed, and the Lambda's code is mounted from disk rather
 * than published as an asset. Defaults to true because the local loop is what this repo
 * actually runs; `-c localMode=false` synthesises the deployable-to-AWS shape.
 */
const localMode = app.node.tryGetContext('localMode') !== 'false';

/**
 * Under LocalStack the "artifact path" is a *directory path handed to the hot-reload
 * bucket*, not a file to upload — LocalStack mounts it as the function's code. Against a
 * real account it is the assembled jar itself.
 */
const lambdaArtifactPath = localMode
  ? path.resolve(__dirname, '../../lambda/target/scala-3.8.4')
  : path.resolve(__dirname, '../../lambda/target/scala-3.8.4/stream-processor-assembly.jar');

// Synthesising for a real account embeds the jar as an asset, so it has to exist first.
// Failing here with the build command is friendlier than CDK's «CannotFindAsset», which
// does not say what produces the missing file.
if (!localMode && !require('fs').existsSync(lambdaArtifactPath)) {
  throw new Error(
    `Lambda artifact not found at ${lambdaArtifactPath}\n` +
      `Run \`sbt lambda/assembly\` first, or synthesise in local mode (the default), ` +
      `where LocalStack mounts the directory instead.`,
  );
}

const data = new DataStack(app, 'PricingData');

new ComputeStack(app, 'PricingCompute', {
  customersTable: data.customersTable,
  couponsTable: data.couponsTable,
  ordersTable: data.ordersTable,
  orderPricedStream: data.orderPricedStream,
  lambdaArtifactPath,
  localMode,
});

app.synth();

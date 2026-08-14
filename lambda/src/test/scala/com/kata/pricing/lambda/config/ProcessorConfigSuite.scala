package com.kata.pricing.lambda.config

import cats.effect.IO
import weaver.SimpleIOSuite

object ProcessorConfigSuite extends SimpleIOSuite:

  /** Only asserts fields whose default this test can actually guarantee. `AWS_REGION` is
    * read from the real process environment by `ProcessorConfig.load` — it is not
    * stubbed here — so asserting `region.id == "us-east-1"` would pass or fail depending
    * on the developer's shell (verified: `AWS_REGION=eu-west-1` flips it to a failure).
    * `streamName` and `concurrency` have no like-named ambient env var, so their
    * defaults are the only ones this suite can call environment-independent. The CDK
    * injects the real values in deployment. */
  test("the config loads from defaults when no environment is set") {
    ProcessorConfig.load[IO].map { config =>
      expect.eql(config.streamName, "order-priced-events") and
        expect.eql(config.concurrency, 4)
    }
  }

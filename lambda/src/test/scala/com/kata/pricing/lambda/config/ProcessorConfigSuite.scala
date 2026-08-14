package com.kata.pricing.lambda.config

import cats.effect.IO
import weaver.SimpleIOSuite

object ProcessorConfigSuite extends SimpleIOSuite:

  /** The defaults must load with no environment set, or a bare `sbt lambda/test` would
    * depend on the developer's shell. The CDK injects the real values. */
  test("the config loads from defaults when no environment is set") {
    ProcessorConfig.load[IO].map { config =>
      expect.eql(config.streamName, "order-priced-events") and
        expect.eql(config.concurrency, 4) and
        expect.eql(config.region.id, "us-east-1")
    }
  }

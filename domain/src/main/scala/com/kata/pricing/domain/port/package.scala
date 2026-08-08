package com.kata.pricing.domain

/** The domain's ports: what the core needs from the outside world, stated as traits over
  * `F[_]` and nothing else.
  *
  * A port names a capability without saying how it is provided. The DynamoDB and http4s
  * adapters that satisfy them live in `service`, and the compiler enforces the split:
  * those libraries are not on `domain`'s classpath, so an adapter could not be written
  * here even by accident. That is DoD rule 1 made structural rather than a matter of
  * discipline.
  *
  * The practical payoff shows up in the tests: a fake is a four-line anonymous class, no
  * mocking framework, and the flow can be instantiated at `Id` or `Either` — see
  * `PricingFlowSuite`.
  */
package object port

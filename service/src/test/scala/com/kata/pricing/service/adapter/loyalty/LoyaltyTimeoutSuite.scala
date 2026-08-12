package com.kata.pricing.service.adapter.loyalty

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.kernel.Outcome
import com.kata.pricing.domain.{CustomerId, Perk, Percent, TraceId}
import com.kata.pricing.domain.port.LoyaltyClient
import weaver.SimpleIOSuite

import scala.concurrent.duration.*

/** DoD rule 6: a test that exercises cats-effect's time control directly, rather than
  * stubbing a return value and asserting it once.
  *
  * The distinction matters. `LoyaltyClientSuite` proves a slow partner degrades to `None`,
  * but it does so by actually sleeping 300ms and trusting that "no perk came back" means
  * the timeout fired — it could equally have been a parse failure. What it cannot show is
  * *when* the timeout fires, or that the call does not block a moment longer than its
  * budget.
  *
  * `TestControl` replaces the runtime's clock with one this test advances by hand. Nothing
  * sleeps: a 500ms timeout is verified in microseconds, and the assertions are about the
  * exact instant work completes — which is the property a wall-clock test cannot express
  * without becoming flaky.
  */
object LoyaltyTimeoutSuite extends SimpleIOSuite:

  private val customer = CustomerId.unsafe("cust-123")
  private val traceId  = TraceId.unsafe("trace-abc")
  private val budget   = 500.millis

  /** A partner whose latency the test dictates. Standing in for the http4s client keeps
    * the subject of the test the timeout policy itself, with no socket involved. */
  private def partner(latency: FiniteDuration, perk: Option[Perk]): LoyaltyClient[IO] =
    (_, _) => IO.sleep(latency).as(perk)

  private def withTimeout(client: LoyaltyClient[IO]): IO[Option[Perk]] =
    client.checkPerk(customer, traceId).timeoutTo(budget, IO.pure(None))

  test("a partner slower than the budget yields no perk, and does so exactly at the budget") {
    val perk = Perk(Percent.unsafe(5))

    TestControl.execute(withTimeout(partner(2.seconds, Some(perk)))).flatMap { control =>
      for
        _        <- control.tick
        // Nothing has completed yet: the partner is still "in flight".
        pending  <- control.results
        _        <- IO(assert(pending.isEmpty))

        // One nanosecond before the deadline the call is still waiting — this is what
        // rules out a timeout that fires early.
        _        <- control.advanceAndTick(budget - 1.nano)
        stillOpen <- control.results
        _        <- IO(assert(stillOpen.isEmpty))

        // Crossing the deadline completes it, with the degraded value.
        _        <- control.advanceAndTick(1.nano)
        outcome  <- control.results
      yield expect(outcome == Some(Outcome.succeeded(None: Option[Perk])))
    }
  }

  test("a partner inside the budget returns its perk, and no time is wasted waiting") {
    val perk = Perk(Percent.unsafe(10))

    // `executeEmbed` runs the program to completion on the simulated clock and fails the
    // test if it deadlocks. The assertion below is about the *elapsed simulated time*:
    // a correct implementation returns as soon as the partner answers, rather than
    // always waiting out the full budget.
    val program = for
      start   <- IO.monotonic
      result  <- withTimeout(partner(100.millis, Some(perk)))
      finish  <- IO.monotonic
    yield (result, finish - start)

    TestControl.executeEmbed(program).map { (result, elapsed) =>
      expect(result == Some(perk)) and expect(elapsed == 100.millis)
    }
  }

  test("the timeout applies per call, so a second call gets a full budget of its own") {
    // Guards against a plausible bug: a timeout installed once around a shared effect
    // would leave the second call with whatever time remained from the first.
    val program = for
      first  <- withTimeout(partner(2.seconds, None))
      mark   <- IO.monotonic
      second <- withTimeout(partner(100.millis, Some(Perk(Percent.unsafe(5)))))
      finish <- IO.monotonic
    yield (first, second, finish - mark)

    TestControl.executeEmbed(program).map { (first, second, secondElapsed) =>
      expect(first.isEmpty) and
        expect(second == Some(Perk(Percent.unsafe(5)))) and
        expect(secondElapsed == 100.millis)
    }
  }

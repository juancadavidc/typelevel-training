package com.kata.pricing.it

import cats.effect.IO

import scala.concurrent.duration.*

/** Bounded polling — the only way this suite is allowed to wait.
  *
  * DynamoDB Streams and Kinesis are eventually consistent: a write is not immediately
  * readable downstream, and there is no callback to await. This is exactly where
  * integration suites become flaky and then get ignored.
  *
  * '''No `IO.sleep` with a constant, anywhere.''' A `sleep(2.seconds)` encodes a guess
  * about how fast the machine is: fast enough on a laptop, too short on a loaded CI
  * runner, and always paying the full two seconds even when the data landed in 50ms. A
  * bounded poll returns as soon as the expected value appears and fails only when it
  * genuinely never did — so it is both faster in the normal case and honest in the
  * failing one.
  *
  * The failure message names what was being waited for, because "timed out after 30s" in
  * a CI log is not a diagnosis.
  */
object Polling:

  private val interval = 200.millis

  /** Repeats `attempt` until it yields a `Some`, or fails after `timeout`.
    *
    * `timeoutTo` rather than `timeout` so the error is this description instead of a bare
    * `TimeoutException` from somewhere inside the retry loop.
    */
  def until[A](timeout: FiniteDuration, description: String)(attempt: IO[Option[A]]): IO[A] =
    def loop: IO[A] = attempt.flatMap {
      case Some(value) => IO.pure(value)
      case None        => IO.sleep(interval) *> loop
    }

    loop.timeoutTo(
      timeout,
      IO.raiseError(
        new AssertionError(s"timed out after $timeout waiting for $description")
      )
    )

  /** Accumulates across polls until `enough` is satisfied.
    *
    * Needed because a stream read is destructive in the sense that matters here: each
    * `GetRecords` advances the iterator, so records arriving across several polls have to
    * be collected as they are seen rather than re-read at the end. The `IO[List[A]]` is
    * therefore a *batch* to append, not the running total.
    */
  def collectUntil[A](timeout: FiniteDuration, description: String)(
      poll: IO[List[A]]
  )(enough: List[A] => Boolean): IO[List[A]] =
    def loop(seen: List[A]): IO[List[A]] =
      if enough(seen) then IO.pure(seen)
      else
        poll.flatMap { batch =>
          val accumulated = seen ++ batch
          if enough(accumulated) then IO.pure(accumulated)
          else IO.sleep(interval) *> loop(accumulated)
        }

    loop(Nil).timeoutTo(
      timeout,
      IO.raiseError(
        new AssertionError(s"timed out after $timeout waiting for $description")
      )
    )

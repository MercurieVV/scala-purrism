/*
rules = [PreferStateThreading]

PreferStateThreading.stateT = true
 */
package golden

import cats.effect.{IO, Ref}

final class StateShapedMethod {

  /** `(S, A) => (S, B)`, and something runs it as one. */
  private def allocate(free: List[Int], pitch: Int): (List[Int], Int) = // assert: PreferStateThreading
    (free.tail, pitch)

  def next(slots: Ref[IO, List[Int]], pitch: Int): IO[Int] =
    slots.modify(free => allocate(free, pitch))

  /** The same shape, threading nothing: it keys a pair. Reporting this as a
    * `State` is the false positive the consumer check exists to remove.
    */
  private def priceKey(agent: String, model: String): (String, String) =
    (agent.toLowerCase, model.toLowerCase)

  /** A poll loop: read the clock, and either stop or go round again. */
  def waitUntil(deadline: Long): IO[Unit] = // assert: PreferStateThreading
    IO.realTime.flatMap { now =>
      if (now.toMillis >= deadline) IO.unit else waitUntil(deadline)
    }

  /** A retry, not a poll: it recurses out of an error handler and counts a
    * budget down. `iterateUntilM` has nowhere to put the giving up.
    */
  def push(retriesLeft: Int): IO[Unit] = // assert: PreferStateThreading
    IO.unit.handleErrorWith { _ =>
      if (retriesLeft > 0) push(retriesLeft - 1) else IO.unit
    }

  /** Recursive, but not a loop over an effect: no `if` hands control back. */
  def depth(values: List[Int]): Int =
    values match {
      case Nil       => 0
      case _ :: rest => 1 + depth(rest)
    }
}

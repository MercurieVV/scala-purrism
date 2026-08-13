/*
rules = [PreferStateThreading]

PreferStateThreading.stateT = true
 */
package golden

import cats.effect.IO

final class StateShapedMethod {
  private def allocate(free: List[Int], pitch: Int): (List[Int], Int) = // assert: PreferStateThreading
    (free.tail, pitch)

  /** A poll loop: read the clock, and either stop or go round again. */
  def waitUntil(deadline: Long): IO[Unit] = // assert: PreferStateThreading
    IO.realTime.flatMap { now =>
      if (now.toMillis >= deadline) IO.unit else waitUntil(deadline)
    }

  /** Recursive, but not a loop over an effect: no `if` hands control back. */
  def depth(values: List[Int]): Int =
    values match {
      case Nil          => 0
      case _ :: rest    => 1 + depth(rest)
    }
}

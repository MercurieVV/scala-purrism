/*
rules = [SuspendSideEffects]
 */
package golden

import cats.effect.IO
import cats.effect.Sync

final class SuspendEagerPure[F[_]: Sync] {
  /** `pure` reads the clock while the value is built, so every run replays it. */
  def startedAt: F[Long] =
    Sync[F].pure(System.nanoTime())

  def stamped: IO[Long] =
    IO.pure(System.currentTimeMillis())
}

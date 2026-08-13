/*
rules = [SuspendSideEffects]
 */
package golden

import cats.effect.IO
import cats.effect.Sync

/** Already the form the rule produces. */
final class SuspendIdempotent[F[_]: Sync] {
  def startedAt: F[Long] =
    Sync[F].delay(System.nanoTime())

  def stamped: IO[Long] =
    IO.delay(System.currentTimeMillis())
}

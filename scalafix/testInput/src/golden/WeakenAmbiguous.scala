/*
rules = [TypeclassWeakening]

# A diamond below the bound: `uncancelable` (MonadCancel) and `monotonic`
# (Clock) are both covered by Sync and by Temporal, and neither is an ancestor
# of the other. One bound is never split in two, so the rule keeps Async and
# reports the choice instead of guessing.
 */
package golden

import cats.effect.Async
import cats.syntax.all.*

object WeakenAmbiguous {
  def timed[F[_]: Async as A](fa: F[Int]): F[Int] = // assert: TypeclassWeakening.ambiguous-weakening
    A.uncancelable(_ => A.monotonic.flatMap(_ => fa))
}

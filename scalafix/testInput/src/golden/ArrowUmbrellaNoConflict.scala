/*
rules = [PreferKleisli, PreferArrow]

# Runs PreferKleisli and PreferArrow together on a single-parameter F[B] def
# whose body is a composition. Both rules could once emit a patch for this def;
# now PreferKleisli steps aside via `leaveToPreferArrow`, so exactly one patch
# lands (PreferArrow's point-free lift) and there is no double-patch conflict.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class UmbrellaFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap(user => loadOrders.run(user))
}

/*
rules = [PreferKleisli]

# Regression fixture, not a PreferArrow fixture. Pins the existing 2-step
# `k1.run(x).flatMap(y => k2.run(y))` -> `k1.andThen(k2)` rewrite
# (TypelevelPurrism.scala:1363) so later PreferArrow/PreferKleisli composition
# work has a real net: "existing behaviour still passes" is falsifiable
# against this fixture.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class UserEnrichment[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap(user => loadOrders.run(user))
}

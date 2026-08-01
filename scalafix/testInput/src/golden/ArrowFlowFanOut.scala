/*
rules = [PreferArrow]

# Pattern C: two Kleislis applied to the same input and combined into a
# tuple, collapsing to `&&&` fan-out. Two entry shapes, both landing on:
#
#   def profile: Kleisli[F, String, (Int, Boolean)] =
#     loadUser &&& loadSettings
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class FanOutFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, Int],
    loadSettings: Kleisli[F, String, Boolean]
) {
  def profile(id: String): F[(Int, Boolean)] =
    loadUser.run(id).flatMap(age => loadSettings.run(id).map(active => (age, active)))

  def profileFor(id: String): F[(Int, Boolean)] =
    for {
      age    <- loadUser.run(id)
      active <- loadSettings.run(id)
    } yield (age, active)
}

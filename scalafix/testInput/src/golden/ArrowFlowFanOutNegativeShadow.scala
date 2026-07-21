/*
rules = [PreferArrow]

# Pattern C negative: the second branch rebinds `id` to a derived value
# before calling `loadSettings.run(id)`. The name is the same as the def's
# own input parameter, but the binding is not -- resolved via SemanticDB
# symbols, not the `Term.Name` spelling -- so the two Kleislis are not
# actually applied to the same input. Not a fan-out; must be left untouched,
# with a Warning-severity diagnostic explaining why (asserted below).
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class FanOutShadowFlow[F[_]: Monad](
    loadUserAge: Kleisli[F, String, Int],
    loadSettings: Kleisli[F, String, Boolean]
) {
  def profile(id: String): F[(Int, Boolean)] =
    loadUserAge.run(id).flatMap { age =>
      val id = age.toString
      loadSettings.run(id).map(active => (age, active)) // assert: PreferArrow
    }
}

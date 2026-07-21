/*
rules = [DisableSyntax]

# PreferArrow does not exist yet; this is a source-of-truth fixture for it,
# not a rewrite fixture. DisableSyntax is configured with nothing here so it
# changes no text, which is why this file needs no matching testOutput entry.
#
# Pattern C negative: the second branch rebinds `id` to a derived value
# before calling `loadSettings.run(id)`. The name is the same as the def's
# own input parameter, but the binding is not — the two Kleislis are not
# actually applied to the same input, so this is not a fan-out and must be
# left untouched.
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
      loadSettings.run(id).map(active => (age, active))
    }
}

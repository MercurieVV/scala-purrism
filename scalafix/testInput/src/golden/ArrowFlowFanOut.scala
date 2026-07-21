/*
rules = [DisableSyntax]

# PreferArrow does not exist yet; this is a source-of-truth fixture for it,
# not a rewrite fixture. DisableSyntax is configured with nothing here so it
# changes no text, which is why this file needs no matching testOutput entry.
#
# Pattern C: two Kleislis applied to the same input and combined into a
# tuple, collapsing to `&&&` fan-out:
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
}

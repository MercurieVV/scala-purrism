/*
rules = [DisableSyntax]

# PreferArrow does not exist yet; this is a source-of-truth fixture for it,
# not a rewrite fixture. DisableSyntax is configured with nothing here so it
# changes no text, which is why this file needs no matching testOutput entry.
#
# Pattern B: a plain `map` after `run`, with no second Kleisli involved:
#
#   def userName: Kleisli[F, String, String] =
#     loadUser.map(_.toString)
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class MapFlow[F[_]: Monad](loadUser: Kleisli[F, String, Int]) {
  def userName(id: String): F[String] =
    loadUser.run(id).map(_.toString)
}

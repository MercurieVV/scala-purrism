/*
rules = [DisableSyntax]

# PreferArrow does not exist yet; this is a source-of-truth fixture for it,
# not a rewrite fixture. DisableSyntax is configured with nothing here so it
# changes no text, which is why this file needs no matching testOutput entry.
#
# Pattern B negative: the mapping function closes over `id`, the def's own
# input parameter. `loadUser.map(name => s"$id:$name")` would leave `id`
# referring to a parameter that no longer exists once the def is rewritten
# to take no argument, so this must be left untouched.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class MapCaptureFlow[F[_]: Monad](loadUser: Kleisli[F, String, String]) {
  def userName(id: String): F[String] =
    loadUser.run(id).map(name => s"$id:$name")
}

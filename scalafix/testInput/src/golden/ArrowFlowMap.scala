/*
rules = [PreferArrow]

# Pattern B: a plain `map` after `run`, with no second Kleisli involved.
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

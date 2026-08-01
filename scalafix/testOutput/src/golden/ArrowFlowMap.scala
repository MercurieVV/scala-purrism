package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class MapFlow[F[_]: Monad](loadUser: Kleisli[F, String, Int]) {
  def userName: Kleisli[F, String, String] =
  loadUser.map(_.toString)
}

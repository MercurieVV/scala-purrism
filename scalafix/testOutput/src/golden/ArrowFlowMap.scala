package golden

import cats.Monad
import cats.data.Kleisli

final class MapFlow[F[_]: Monad](loadUser: Kleisli[F, String, Int]) {
  def userName: Kleisli[F, String, String] =
    loadUser.map(_.toString)
}

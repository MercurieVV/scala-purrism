package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.arrow._

final class FanOutFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, Int],
    loadSettings: Kleisli[F, String, Boolean]
) {
  def profile: Kleisli[F, String, (Int, Boolean)] =
    loadUser &&& loadSettings
}

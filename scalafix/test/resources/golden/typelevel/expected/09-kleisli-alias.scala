package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](
    profile: Kleisli[F, String, Profile]
) {
  def loadProfile: Kleisli[F, String, Profile] =
    profile
}

final case class Profile(id: String)

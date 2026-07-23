package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](
    profile: Kleisli[F, String, Profile]
) {
  def loadProfile(id: String): F[Profile] =
    profile.run(id)
}

final case class Profile(id: String)

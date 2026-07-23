package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](
    fetch: Kleisli[F, String, User],
    profile: Kleisli[F, User, Profile]
) {
  def loadProfile: Kleisli[F, String, Profile] =
    fetch.andThen(profile)
}

final case class User(id: String)
final case class Profile(id: String)

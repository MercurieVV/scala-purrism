package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](
    fetch: Kleisli[F, String, User],
    profile: Kleisli[F, User, Profile]
) {
  def loadProfile(id: String): F[Profile] =
    fetch(id).flatMap(user => profile(user))
}

final case class User(id: String)
final case class Profile(id: String)

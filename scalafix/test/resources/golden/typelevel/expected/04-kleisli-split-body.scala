package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](profile: Kleisli[F, User, Profile]) {
  def loadProfile: Kleisli[F, String, Profile] =
    profile.local { id =>
      val user = User(id)
      user
    }
}

final case class User(id: String)
final case class Profile(id: String)

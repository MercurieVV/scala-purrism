package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](profile: Kleisli[F, User, Profile]) {
  def loadProfile: Kleisli[F, String, Profile] =
    Kleisli.apply { id =>
      val user = User(id)
      profile.run(user)
    }
}

final case class User(id: String)
final case class Profile(id: String)

package examples

import cats.Monad
import cats.syntax.all.*

final class UserService[F[_]: Monad](profiles: Profiles[F]) {
  def load(seed: F[User]): F[Profile] =
    seed.flatMap(user => profiles.fetch(user.id))
}

trait Profiles[F[_]] {
  def fetch(id: String): F[Profile]
}

final case class User(id: String)
final case class Profile(id: String)

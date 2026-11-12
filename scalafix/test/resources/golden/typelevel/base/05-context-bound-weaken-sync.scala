package examples

import cats.effect.Sync
import cats.syntax.all.*

final class UserService[F[_]: Sync](fetch: String => F[Profile]) {
  def load(seed: F[User]): F[Profile] =
    seed.flatMap(user => fetch(user.id))
}

final case class User(id: String)
final case class Profile(id: String)

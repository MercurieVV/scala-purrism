package examples

import cats.Monad
import cats.data.Kleisli

final class UserService[F[_]: Monad](client: HttpClient[F]) {
  def fetch: Kleisli[F, String, User] =
    Kleisli.apply { id =>
      client.get(id)
    }

  def program(id: String): F[User] =
    fetch(id)
}

trait HttpClient[F[_]] {
  def get(id: String): F[User]
}

final case class User(id: String)

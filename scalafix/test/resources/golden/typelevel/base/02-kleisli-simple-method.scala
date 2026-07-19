package examples

import cats.Monad

final class UserService[F[_]: Monad](client: HttpClient[F]) {
  def fetch(id: String): F[User] =
    client.get(id)

  def program(id: String): F[User] =
    fetch(id)
}

trait HttpClient[F[_]] {
  def get(id: String): F[User]
}

final case class User(id: String)

package examples

import cats.MonadThrow
import cats.syntax.all.*

final class UserService[F[_]: MonadThrow](client: HttpClient[F]) {
  def fetch(id: String): F[User] =
    client.get(id)
}

trait HttpClient[F[_]] {
  def get(id: String): F[User]
}

final case class User(id: String)

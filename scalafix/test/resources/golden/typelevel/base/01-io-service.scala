package examples

import cats.effect.IO

final class UserService(client: HttpClient) {
  def fetch(id: String): IO[User] =
    client.get(id)
}

trait HttpClient {
  def get(id: String): IO[User]
}

final case class User(id: String)

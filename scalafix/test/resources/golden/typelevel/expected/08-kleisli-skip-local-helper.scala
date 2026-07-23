package examples

import cats.Monad
import cats.data.Kleisli
import cats.syntax.all.*

final class PollingService[F[_]: Monad](client: Client[F]) {
  def fetch: Kleisli[F, String, User] =
    Kleisli.apply { id =>
      client.get(id)
    }

  def poll(taskId: Int): F[Issue] =
    def loop(deadlineMillis: Long): F[Issue] =
      client.issue(taskId).flatMap {
        case issue if issue.closed => issue.pure[F]
        case _                     => loop(deadlineMillis)
      }

    loop(1000L)
}

trait Client[F[_]] {
  def get(id: String): F[User]
  def issue(taskId: Int): F[Issue]
}

final case class User(id: String)
final case class Issue(number: Int, closed: Boolean)

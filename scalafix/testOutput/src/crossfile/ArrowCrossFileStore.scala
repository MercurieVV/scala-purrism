
package crossfile

import cats.Monad
import cats.data.Kleisli

object ArrowCrossFileStore {
  def summarise[F[_]: Monad](tag: String): Kleisli[F, (Int, String), Unit] =
    Kleisli { case (id, label) => Monad[F].unit }
}

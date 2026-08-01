
package crossfile

import cats.effect.Sync
import cats.data.Kleisli

object CrossFileStore {
  def load[F[_]: Sync]: Kleisli[F, (String, Int), String] =
  Kleisli.apply { case (root, id) =>
    Sync[F].pure(s"$root/$id")
  }
}

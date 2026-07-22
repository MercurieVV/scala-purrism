
package crossfile

import cats.effect.Sync

object CrossFileCaller {
  def describe[F[_]: Sync]: F[String] =
    CrossFileStore.load[F](("/tmp", 7))
}

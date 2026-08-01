/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The *definition* half of the cross-file arrow pair. Nothing here is rewritten;
# the file exists so that `summarise` is declared somewhere other than the file
# under rewrite. See ArrowCrossFileCaller.scala for the half that matters.
 */
package crossfile

import cats.Monad
import cats.data.Kleisli

object ArrowCrossFileStore {
  def summarise[F[_]: Monad](tag: String): Kleisli[F, (Int, String), Unit] =
    Kleisli { case (id, label) => Monad[F].unit }
}

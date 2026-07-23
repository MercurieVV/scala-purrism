/*
rules = [PreferKleisli]
PreferKleisli.crossFile = true

# The *definition* half of the cross-file pair. `load` is public, so its
# callers can live in any file; the project-wide lift scope is what lets it be
# re-shaped at all. See CrossFileCaller.scala for the calling half -- both
# files must be rewritten consistently or the result does not compile.
 */
package crossfile

import cats.effect.Sync

object CrossFileStore {
  def load[F[_]: Sync](root: String, id: Int): F[String] =
    Sync[F].pure(s"$root/$id")
}

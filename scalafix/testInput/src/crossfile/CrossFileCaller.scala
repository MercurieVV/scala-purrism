/*
rules = [PreferKleisli]
PreferKleisli.crossFile = true

# The *calling* half of the cross-file pair. `CrossFileStore.load` is defined
# in another file, so nothing in this document could tell it was re-shaped --
# only the project-wide scope can, and this fixture is what proves the call
# site follows the definition.
 */
package crossfile

import cats.effect.Sync

object CrossFileCaller {
  def describe[F[_]: Sync]: F[String] =
    CrossFileStore.load[F]("/tmp", 7)
}

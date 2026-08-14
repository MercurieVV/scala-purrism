
package golden

import cats.effect.Sync
import cats.syntax.all.*

/** Shapes that more than one rule has an opinion about, in one pass.
  *
  * Each rule de-overlaps its own patches; nothing de-overlaps across rules. So
  * two rules claiming one expression is the case worth pinning, and the fold
  * `PreferOptionIdioms` produces is exactly what `PreferEffectIdioms` consumes.
  */
final class IdiomCrossRule[F[_]: Sync] {

  /** `PreferOptionIdioms` makes this a `fold`; the `fold` it makes is the one
    * `PreferEffectIdioms` turns into `traverse_`.
    */
  def announce(name: Option[String], log: String => F[Unit]): F[Unit] =
    name.fold(Sync[F].unit)(log)

  /** `PreferIndexedMap` rewrites the body while `PreferContainerTypeclasses`
    * rewrites the signature of the same definition.
    */
  private def total(rows: List[Int]): Int =
    rows.foldMap(row => row + 1)
}

/*
rules = [TypelevelPurrism]

# Under the umbrella, `PreferKleisli` rewrites `bumped` into a Kleisli and
# copies its type parameters verbatim. Weakening the same def's bound would
# patch a token inside the tree the Kleisli rewrite replaces, so the
# weakening steps aside for every def another member rewrites; a def no other
# member touches is still weakened.
 */
package golden

import cats.effect.Sync
import cats.syntax.all.*

final case class UmbrellaRoot(offset: Int)

object WeakenUmbrellaOverlap {
  def bumped[F[_]: Sync](root: UmbrellaRoot, base: F[Int]): F[Int] =
    base.map(_ + root.offset)

  def paired[F[_]: Sync](fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.product(fb)

  def lifted[F[_]: Sync]: F[Int] =
    1.pure[F].map(_ + 1)
}

/*
rules = [TypeclassWeakening]

# A type parameter holding more than one evidence keeps all its bounds:
# solving each alone would emit `F[_]: Functor: Functor`, two identical
# givens. A def is also kept when one of its call sites in this file has two
# or more evidences for the type argument that descend from the weakened
# target: `bump(fa)` inside `caller` would find both `Temporal[F]` and
# `Sync[F]` for `Functor[F]` and be ambiguous. A def called only from
# single-evidence scopes still weakens.
 */
package golden

import cats.effect.Sync
import cats.effect.Temporal
import cats.syntax.all.*

object WeakenSeveralEvidences {
  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def caller[F[_]: Temporal: Sync](fa: F[Int]): F[Int] =
    bump(fa)

  def inc[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def explicit[F[_]: Sync](fa: F[Int])(using T: Temporal[F]): F[Int] =
    inc(fa)

  def plain[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 2)

  def usesPlain[F[_]: Sync](fa: F[Int]): F[Int] =
    plain(fa)
}

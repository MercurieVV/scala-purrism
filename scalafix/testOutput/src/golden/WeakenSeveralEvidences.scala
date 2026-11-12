package golden

import cats.effect.Sync
import cats.effect.Temporal
import cats.syntax.all.*
import cats.Functor

object WeakenSeveralEvidences {
  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def caller[F[_]: Temporal: Sync](fa: F[Int]): F[Int] =
    bump(fa)

  def inc[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def explicit[F[_]: Sync](fa: F[Int])(using T: Temporal[F]): F[Int] =
    inc(fa)

  def plain[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 2)

  def usesPlain[F[_]: Functor](fa: F[Int]): F[Int] =
    plain(fa)
}

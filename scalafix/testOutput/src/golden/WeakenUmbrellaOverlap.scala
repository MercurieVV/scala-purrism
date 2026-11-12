
package golden

import cats.effect.Sync
import cats.syntax.all.*
import cats.Applicative
import cats.data.Kleisli

final case class UmbrellaRoot(offset: Int)

object WeakenUmbrellaOverlap {
  def bumped[F[_]: Sync]: Kleisli[F, (UmbrellaRoot, F[Int]), Int] =
  Kleisli.apply { case (root, base) =>
    base.map(_ + root.offset)
  }

  def paired[F[_]: Sync]: Kleisli[F, (F[Int], F[Int]), (Int, Int)] =
  Kleisli.apply { case (fa, fb) =>
    fa.product(fb)
  }

  def lifted[F[_]: Applicative]: F[Int] =
    1.pure[F].map(_ + 1)
}

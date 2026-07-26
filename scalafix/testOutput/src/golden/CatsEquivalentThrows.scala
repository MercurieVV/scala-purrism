package golden

import cats.Monad
import cats.syntax.flatMap._

final class Throws[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap { a =>
      if (a < 0) throw new IllegalArgumentException("negative")
      else pure(a)
    }

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

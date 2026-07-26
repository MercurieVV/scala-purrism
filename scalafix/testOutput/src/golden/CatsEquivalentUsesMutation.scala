package golden

import cats.Monad
import cats.syntax.flatMap._

final class UsesMutation[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] = {
    var count = 0
    fa.flatMap { a =>
      count += 1
      pure(a)
    }
  }

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

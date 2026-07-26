package golden

import cats.Monad
import cats.syntax.flatMap._

final class EvaluatesTwice[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => fa.flatMap(_ => pure(a)))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

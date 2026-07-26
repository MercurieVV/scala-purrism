package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class MissingTypeclassEvidence[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => pure(a + 1))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

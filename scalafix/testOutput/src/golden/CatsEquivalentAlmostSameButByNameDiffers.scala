package golden

import cats.Monad
import cats.syntax.flatMap._

final class ByNameDiffers[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => byNameFlatMap(fa, a))

  private def byNameFlatMap[A](fa: F[A], a: A)(implicit F: Monad[F]): F[A] =
    fa.flatMap(_ => F.pure(a))
}

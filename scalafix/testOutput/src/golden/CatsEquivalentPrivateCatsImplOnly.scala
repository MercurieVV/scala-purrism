package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class PrivateCatsImplOnly[F[_]: Monad] {
  def test(fa: F[Int]): F[Unit] =
    fa.flatMap(_ => pure(()))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

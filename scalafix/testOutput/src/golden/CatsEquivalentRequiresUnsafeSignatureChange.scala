package golden

import cats.Monad
import cats.syntax.functor._

final class RequiresUnsafeSignatureChange[F[_]: Monad] {
  def test(fa: F[Int]): F[String] =
    fa.map(_.toString)
}

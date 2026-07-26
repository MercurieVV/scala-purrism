package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class AmbiguousCatsMatches[F[_]: Monad] {
  def test(fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.flatMap(a => fb.map(b => (a, b)))
}

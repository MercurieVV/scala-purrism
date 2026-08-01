
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyMapN[F[_]: Monad] {
  def combine(first: F[Int], second: F[String]): F[(Int, String)] =
    (first, second).mapN((value, label) => (value, label))
}

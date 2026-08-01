/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyMapN[F[_]: Monad] {
  def combine(first: F[Int], second: F[String]): F[(Int, String)] =
    first.flatMap(value => second.map(label => (value, label)))
}

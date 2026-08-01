/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.FlatMap
import cats.syntax.all.*

final class CatsSimplifySequence[F[_]: FlatMap] {
  def sequence(first: F[Unit], second: F[String]): F[String] =
    first.flatMap(_ => second)
}

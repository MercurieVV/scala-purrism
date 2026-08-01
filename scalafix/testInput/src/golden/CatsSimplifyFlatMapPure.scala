/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyFlatMapPure[F[_]: Monad] {
  def mapped(seed: F[Int]): F[String] =
    seed.flatMap(value => value.toString.pure[F])
}

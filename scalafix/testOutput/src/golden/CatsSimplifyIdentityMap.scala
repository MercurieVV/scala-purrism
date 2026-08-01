
package golden

import cats.Functor
import cats.syntax.all.*

final class CatsSimplifyIdentityMap[F[_]: Functor] {
  def unchangedWithIdentity(seed: F[Int]): F[Int] =
    seed

  def unchangedWithLambda(seed: F[Int]): F[Int] =
    seed
}

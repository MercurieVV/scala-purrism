/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyFlatten[F[_]: Monad] {
  def joinWithIdentity(nested: F[F[Int]]): F[Int] =
    nested.flatMap(identity)

  def joinWithLambda(nested: F[F[Int]]): F[Int] =
    nested.flatMap(inner => inner)
}

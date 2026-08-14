
package golden

import cats.Functor
import cats.syntax.all.*

final class CatsSimplifyAs[F[_]: Functor] {
  def replace(seed: F[Int]): F[String] =
    seed.as("done")

  /** A two-parameter lambda is not a constant one. Collapsing it to `as` drops
    * both binders and leaves names that no longer resolve.
    */
  def pairs(values: F[(Int, Int)]): F[Int] =
    values.map((left, right) => left + right)
}

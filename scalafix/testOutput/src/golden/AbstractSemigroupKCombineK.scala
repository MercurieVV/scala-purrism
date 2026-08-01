
package golden

import cats.syntax.semigroupk._
import cats.SemigroupK

object AbstractSemigroupKCombineK {
  private def combine[G[_]: SemigroupK](x: G[Int], y: G[Int]): G[Int] = x <+> y
}

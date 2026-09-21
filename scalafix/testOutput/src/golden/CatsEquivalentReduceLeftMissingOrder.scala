
package golden

import cats.Reducible
import cats.syntax.all._

final class ReduceLeftMissingOrder[F[_]: Reducible] {
  def test(fa: F[Int]): Int =
    fa.reduceLeft(cats.Order[Int].max) 
}

/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.semigroupk._

object AbstractSemigroupKCombineK {
  private def combine(x: List[Int], y: List[Int]): List[Int] = x <+> y
}

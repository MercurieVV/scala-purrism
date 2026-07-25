/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.apply._

object AbstractApplyMap2 {
  private def pair(xs: List[Int], ys: List[Int]): List[Int] = xs.map2(ys)(_ + _)
}

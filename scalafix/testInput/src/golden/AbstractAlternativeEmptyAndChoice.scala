/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.Alternative

object AbstractAlternativeEmptyAndChoice {
  private def choose[G[_]: Alternative](x: G[Int], y: G[Int]): G[Int] = Alternative[G].combineK(x, y)
}

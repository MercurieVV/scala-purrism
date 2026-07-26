/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.semigroupk._

object AbstractAlternativeEmptyAndChoice {
  private def choose[G[_]: Alternative](x: G[Int], y: G[Int]): G[Int] = x <+> y
}

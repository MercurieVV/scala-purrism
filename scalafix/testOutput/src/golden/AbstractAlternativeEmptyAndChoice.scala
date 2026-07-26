/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Alternative
import cats.syntax.alternative._

object AbstractAlternativeEmptyAndChoice {
  private def choose[G[_]: Alternative](x: G[Int], y: G[Int]): G[Int] = x <+> y
}

/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Alternative
import cats.syntax.semigroupk._

private def combine[G[_]: Alternative](x: G[Int], y: G[Int]): G[Int] = x <+> y

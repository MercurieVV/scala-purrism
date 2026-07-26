/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Reducible
import cats.syntax.reducible._

private def sum[G[_]: Reducible](xs: G[Int]): Int = xs.reduce

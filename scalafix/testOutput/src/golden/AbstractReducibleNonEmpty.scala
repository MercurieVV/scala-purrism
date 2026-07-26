/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Reducible

private def sum[G[_]: Reducible](xs: G[Int]): Int = Reducible[G].reduce(xs)

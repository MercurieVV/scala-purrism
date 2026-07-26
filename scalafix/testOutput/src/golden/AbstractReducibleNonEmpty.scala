/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Reducible
import cats.syntax.all._

private def sum[G[_]: Reducible](xs: G[Int]): Int = xs.reduce


package golden

import cats.Reducible
import cats.data.NonEmptyList

private def sum[G[_]: Reducible](xs: G[Int]): Int = Reducible[G].reduce(xs)

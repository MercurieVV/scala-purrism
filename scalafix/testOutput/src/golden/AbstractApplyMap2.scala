
package golden

import cats.Apply
import cats.syntax.apply._

object AbstractApplyMap2 {
  private def pair[G[_]: Apply](xs: G[Int], ys: G[Int]): G[Int] = xs.map2(ys)(_ + _)
}

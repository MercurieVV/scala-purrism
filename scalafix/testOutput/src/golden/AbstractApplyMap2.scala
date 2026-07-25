
package golden

import cats.syntax.apply._
import cats.Apply

object AbstractApplyMap2 {
  private def pair[G[_]: Apply](xs: G[Int], ys: G[Int]): G[Int] = xs.map2(ys)(_ + _)
}


package golden

import cats.Defer

object AbstractDefer {
  private def repeat[G[_]: Defer](value: G[Int]): G[Int] =
    Defer[G].defer(repeat(value))
}

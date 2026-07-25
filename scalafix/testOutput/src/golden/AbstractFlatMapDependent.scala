
package golden

import cats.FlatMap
import cats.syntax.flatMap._
import cats.syntax.functor._

object AbstractFlatMapDependent {
  private def chain[G[_]: FlatMap](xs: G[Int])(f: Int => G[Int]): G[Int] =
    xs.flatMap(f).map(_ * 2)
}

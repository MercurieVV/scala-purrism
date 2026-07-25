
package golden

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.FlatMap

object AbstractFlatMapDependent {
  private def chain[G[_]: FlatMap](xs: G[Int])(f: Int => G[Int]): G[Int] =
    xs.flatMap(f).map(_ * 2)
}

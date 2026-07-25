/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.flatMap._
import cats.syntax.functor._

object AbstractFlatMapDependent {
  private def chain(xs: List[Int])(f: Int => List[Int]): List[Int] =
    xs.flatMap(f).map(_ * 2)
}

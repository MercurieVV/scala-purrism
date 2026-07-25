/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.traverse._

object AbstractTraverseListTraverse {
  private def all(xs: List[Int]): Option[List[Int]] = {
    val f: Int => Option[Int] = x => if (x > 0) Some(x) else None
    xs.traverse(f)
  }
}

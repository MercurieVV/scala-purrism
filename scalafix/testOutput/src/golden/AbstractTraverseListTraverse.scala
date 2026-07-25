
package golden

import cats.Traverse
import cats.syntax.traverse._

object AbstractTraverseListTraverse {
  private def all[G[_]: Traverse](xs: G[Int]): Option[G[Int]] = {
    val f: Int => Option[Int] = x => if (x > 0) Some(x) else None
    xs.traverse(f)
  }
}

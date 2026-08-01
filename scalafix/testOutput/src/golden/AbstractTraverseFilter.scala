
package golden

import cats.TraverseFilter

object AbstractTraverseFilter {
  private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] =
            TraverseFilter[G].traverseFilter(xs)(i => Option(Option(i)))
}

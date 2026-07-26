/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.TraverseFilter
import cats.syntax.traverseFilter._

object AbstractTraverseFilter {
  private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] = xs.traverseFilter(i => Option(Option(i)))
}

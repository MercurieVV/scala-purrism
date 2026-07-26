/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.traverseFilter._

object AbstractTraverseFilter {
  private def filterMap(xs: List[Int]): Option[List[Int]] = xs.traverseFilter(i => Option(Option(i)))
}

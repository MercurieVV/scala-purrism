/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.TraverseFilter

object AbstractTraverseFilter {
  private def filterMap(xs: List[Int]): Option[List[Int]] = TraverseFilter[List].traverseFilter(xs)(i => Option(Option(i)))
}

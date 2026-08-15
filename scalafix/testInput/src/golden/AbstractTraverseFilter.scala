/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.TraverseFilter

object AbstractTraverseFilter {
  // Not widened: summon-style body, see AbstractReducibleNonEmpty.
  private def filterMap(xs: List[Int]): Option[List[Int]] = TraverseFilter[List].traverseFilter(xs)(i => Option(Option(i)))
}

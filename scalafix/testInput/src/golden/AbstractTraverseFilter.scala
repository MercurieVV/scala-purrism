/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.traverseFilter._

private def filterMap(xs: List[Int]): Option[List[Int]] = xs.traverseFilter(i => Option(i).filter(_ => true))

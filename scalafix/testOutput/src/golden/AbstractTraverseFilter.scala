/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.TraverseFilter
import cats.syntax.traverseFilter._

private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] = xs.traverseFilter(i => Option(i).filter(_ => true))

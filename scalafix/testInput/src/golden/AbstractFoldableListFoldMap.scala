/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.foldable._

object AbstractFoldableListFoldMap {
  private def concat(xs: List[Int]): String = xs.foldMap(_.toString)
}

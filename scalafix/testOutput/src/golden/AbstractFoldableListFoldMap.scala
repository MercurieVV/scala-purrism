
package golden

import cats.syntax.foldable._
import cats.Foldable

object AbstractFoldableListFoldMap {
  private def concat[G[_]: Foldable](xs: G[Int]): String = xs.foldMap(_.toString)
}


package golden

import cats.Foldable
import cats.syntax.foldable._

object AbstractFoldableListFoldMap {
  private def concat[G[_]: Foldable](xs: G[Int]): String = xs.foldMap(_.toString)
}

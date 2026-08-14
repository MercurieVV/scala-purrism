
package crossfile

import cats.Foldable
import cats.syntax.foldable._
object WidenCrossFileDef {
  def render[A, S[_]: Foldable](rows: S[A], prefix: String): String =
    prefix + rows.toList.size
}

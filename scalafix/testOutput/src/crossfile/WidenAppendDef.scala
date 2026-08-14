
package crossfile

import cats.Show
import cats.Functor
import cats.syntax.functor._

object WidenAppendDef {
  def describe[A: Show, S[_]: Functor](rows: S[Int]): S[String] =
    rows.map(row => row.toString)
}

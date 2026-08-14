
package golden

import cats.{Foldable, Show}
import cats.syntax.foldable._
final class ElementShowMkString {

  /** Concrete element Cats ships a `Show` for. */
  private def rendered[S[_]: Foldable](rows: S[String]): String =
    rows.mkString_("[", ",", "]")

  /** Element is the definition's own type parameter: it gets the bound. */
  private def joined[A, S[_]: Foldable](rows: S[A])(using Show[A]): String =
    rows.mkString_(", ")

  /** `sum` is `combineAll` under `Monoid`, not `Numeric`. */
  private def total[S[_]: Foldable](rows: S[Int]): Int =
    rows.combineAll
}

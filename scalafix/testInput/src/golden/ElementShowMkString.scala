/*
rules = [PreferElementTypeclasses]
 */
package golden

final class ElementShowMkString {

  /** Concrete element Cats ships a `Show` for. */
  private def rendered(rows: List[String]): String =
    rows.mkString("[", ",", "]")

  /** Element is the definition's own type parameter: it gets the bound. */
  private def joined[A](rows: List[A]): String =
    rows.mkString(", ")

  /** `sum` is `combineAll` under `Monoid`, not `Numeric`. */
  private def total(rows: List[Int]): Int =
    rows.sum
}

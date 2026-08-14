/*
rules = [PreferElementTypeclasses]
 */
package golden

final case class Reading(volts: Double)

/** The element is a domain type. Cats ships no `Show[Reading]`, and `mkString`
  * would have used its `toString`, so there is nothing to rewrite to.
  */
final class ElementUnknownInstanceDecline {
  private def rendered(rows: List[Reading]): String =
    rows.mkString("[", ",", "]")
}

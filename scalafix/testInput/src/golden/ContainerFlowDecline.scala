/*
rules = [PreferPolymorphicCollections]
 */
package golden

import scala.concurrent.duration.FiniteDuration

/** Two bodies whose parameter looks abstractable and is not. Each one compiled
  * as a concrete collection and would not compile as an `S[_]`.
  */
final class ContainerFlowDecline {

  /** `mkString` is not any Cats capability, so the analyzer declines the
    * definition before a constraint set is ever solved -- silently, because a
    * body full of element-level calls it cannot resolve is the normal case and
    * not something to report.
    */
  private def rendered(rows: List[String]): String =
    rows.map(row => row.trim).mkString("[", ",", "]")

  /** The mapped value is handed to something that asked for a `Seq`. */
  private def wrapped(rows: Seq[String], width: FiniteDuration): Padded = // assert: PreferPolymorphicCollections
    Padded(rows.map(row => row.take(3)), width)
}

final case class Padded(rows: Seq[String], width: FiniteDuration)

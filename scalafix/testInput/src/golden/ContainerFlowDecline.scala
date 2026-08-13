/*
rules = [PreferContainerTypeclasses]
 */
package golden

import scala.concurrent.duration.FiniteDuration

/** Three bodies whose parameter looks abstractable and is not. Each one
  * compiled as a concrete collection and would not compile as an `S[_]`.
  */
final class ContainerFlowDecline {

  /** `filter` is `FunctorFilter`, not `Functor`; solving on `map` alone drops it. */
  private def positive(rows: List[Int]): List[Int] = // assert: PreferContainerTypeclasses
    rows.map(row => row + 1).filter(row => row > 0)

  /** `mkString` is not any Cats capability. */
  private def rendered(rows: List[String]): String = // assert: PreferContainerTypeclasses
    rows.map(row => row.trim).mkString("[", ",", "]")

  /** The mapped value is handed to something that asked for a `Seq`. */
  private def wrapped(rows: Seq[String], width: FiniteDuration): Padded = // assert: PreferContainerTypeclasses
    Padded(rows.map(row => row.take(3)), width)
}

final case class Padded(rows: Seq[String], width: FiniteDuration)


package golden

import scala.util.Try

final class IdiomFoldOverMap {

  def label(name: Option[String]): String =
    name.fold("unknown")(value => value.trim)

  /** `Either#fold` takes both branches in one argument list, so the curried
    * form this rule emits would not compile. Matched by symbol, so it does not
    * fire here.
    */
  def parsed(value: Either[String, Int]): Int =
    value.map(number => number + 1).getOrElse(0)

  /** Same for `Try`. */
  def attempted(value: Try[Int]): Int =
    value.map(number => number + 1).getOrElse(0)
}

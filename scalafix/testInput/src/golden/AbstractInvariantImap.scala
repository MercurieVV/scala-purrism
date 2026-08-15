/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.Semigroup
import cats.syntax.invariant.*

object AbstractInvariantImap {
  private def lift(sg: Semigroup[String]): Semigroup[Int] =
    sg.imap(_.length)("a" * _)
}

package golden

import cats.Semigroup
import cats.syntax.invariant._

object AbstractInvariantImap {
  private def lift(sg: Semigroup[String]): Semigroup[Int] = sg.imap(_.length)("a" * _)
}

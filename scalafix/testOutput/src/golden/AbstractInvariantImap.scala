
package golden

import cats.Invariant
import cats.syntax.invariant.*

object AbstractInvariantImap {
  private def lift[G[_]: Invariant](sg: G[String]): G[Int] =
    sg.imap(_.length)("a" * _)
}

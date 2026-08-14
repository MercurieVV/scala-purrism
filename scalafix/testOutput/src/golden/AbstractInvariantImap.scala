
package golden

import cats.syntax.invariant.*
import cats.Invariant

object AbstractInvariantImap {
  private def lift[G[_]: Invariant](sg: G[String]): G[Int] =
    sg.imap(_.length)("a" * _)
}

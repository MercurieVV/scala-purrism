
package golden

import cats.syntax.coflatMap.*
import cats.syntax.comonad.*
import cats.Comonad

object AbstractComonadExtractCoflatMap {
  private def duplicate[G[_]: Comonad](e: G[Int]): G[Int] =
    e.coflatMap(w => w.extract + 1)
}

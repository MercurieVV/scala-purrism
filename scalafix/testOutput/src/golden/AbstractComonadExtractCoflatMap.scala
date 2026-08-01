
package golden

import cats.Comonad
import cats.syntax.coflatMap.*
import cats.syntax.comonad.*

object AbstractComonadExtractCoflatMap {
  private def duplicate[G[_]: Comonad](e: G[Int]): G[Int] =
    e.coflatMap(w => w.extract + 1)
}

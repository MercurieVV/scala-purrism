package golden

import cats.Comonad
import cats.syntax.coflatMap._
import cats.syntax.comonad._

object AbstractComonadExtractCoflatMap {
  private def duplicate[G[_]: Comonad](e: G[Int]): G[Int] = e.coflatMap(w => w.extract + 1)
}

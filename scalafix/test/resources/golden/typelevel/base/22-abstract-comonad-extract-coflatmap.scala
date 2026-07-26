package golden

import cats.Eval
import cats.syntax.coflatMap._
import cats.syntax.comonad._

object AbstractComonadExtractCoflatMap {
  private def duplicate(e: Eval[Int]): Eval[Int] = e.coflatMap(w => w.extract + 1)
}

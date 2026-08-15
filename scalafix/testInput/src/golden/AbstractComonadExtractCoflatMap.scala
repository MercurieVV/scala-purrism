/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.Eval
import cats.syntax.coflatMap.*
import cats.syntax.comonad.*

object AbstractComonadExtractCoflatMap {
  private def duplicate(e: Eval[Int]): Eval[Int] =
    e.coflatMap(w => w.extract + 1)
}

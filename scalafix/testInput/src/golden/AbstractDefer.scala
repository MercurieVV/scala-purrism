/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Eval

object AbstractDefer {
  private def repeat(value: Eval[Int]): Eval[Int] =
    Eval.defer(repeat(value))
}

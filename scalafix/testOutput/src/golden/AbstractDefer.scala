
package golden

import cats.Eval

object AbstractDefer {
  // Not widened: `Eval.defer` is a companion call, see
  // AbstractMonadErrorEitherRaiseHandle.
  private def repeat(value: Eval[Int]): Eval[Int] =
    Eval.defer(repeat(value))
}

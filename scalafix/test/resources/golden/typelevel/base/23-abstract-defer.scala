package golden

import cats.Eval
import cats.syntax.all._

object AbstractDefer {
  private def fib(n: Int): Eval[Int] =
    if (n <= 1) Eval.now(n)
    else Eval.defer(fib(n - 1).flatMap(a => fib(n - 2).map(a + _)))
}

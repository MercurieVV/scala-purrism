package golden

import cats.Defer
import cats.Monad
import cats.syntax.all._

object AbstractDefer {
  private def fib[G[_]: Monad: Defer](n: Int): G[Int] =
    if (n <= 1) n.pure[G]
    else Defer[G].defer(fib(n - 1).flatMap(a => fib(n - 2).map(a + _)))
}

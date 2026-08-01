/*
rules = [PreferCatsSyntax]

# `Monad` here is a local object, not cats.Monad. Dispatching on the spelling
# rewrites `Monad[Int].pure(value)` to `value.pure[Int]`, which is a different
# method on a different type. This file must come back unchanged.
 */
package golden

object LocalPure {
  def pure[A](a: A): A = a
}

object Monad {
  def apply[A]: LocalPure.type = LocalPure
}

final class CatsNegativeLocalMonadObject {
  def lift(value: String): String =
    Monad[Int].pure(value)
}

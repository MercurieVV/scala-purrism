/*
rules = [PreferCatsFunctions]

# P1: Normalized body matches a Cats function except evaluation order differs.
# The user code evaluates fb before fa, but the Cats reference evaluates fa
# before fb. Since evaluation order can have observable effects, this is not
# equivalent and must not be rewritten. Warning only if confidently detected.
 */
package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class OrderDiffers[F[_]: Monad] {
  def test(fa: F[Int], fb: F[String]): F[(String, Int)] =
    fb.flatMap(b => fa.map(a => (b, a)))
}

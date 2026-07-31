/*
rules = [PreferCatsFunctions]

# P5: Normalized body matches a Cats function except the user code uses a local
# var (mutable state) where the Cats reference is pure. Presence of mutable
# state is an effect that cannot be abstracted away; this is not equivalent and
# must not be rewritten. Warning only if confidently detected.
 */
package golden

import cats.Monad
import cats.syntax.flatMap._

final class UsesMutation[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] = {
    var count = 0
    fa.flatMap { a =>
      count += 1
      pure(a)
    }
  }

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

/*
rules = [PreferCatsFunctions]

# P4: Normalized body matches a Cats function except the user code contains
# throw or relies on exception control flow where the Cats reference models
# error functionally. Both are non-local control transfer that cannot be
# abstracted away; this is not equivalent and must not be rewritten. Warning
# only if confidently detected.
 */
package golden

import cats.Monad
import cats.syntax.flatMap._

final class Throws[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap { a =>
      if (a < 0) throw new IllegalArgumentException("negative")
      else pure(a)
    }

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

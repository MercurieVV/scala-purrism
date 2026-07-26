/*
rules = [PreferCatsFunctions]

# P2: Normalized body matches a Cats function except an effectful subexpression
# is evaluated twice instead of once (or vice versa). Since the count of
# evaluations changes observable behavior with F effects, this is not
# equivalent and must not be rewritten. Warning only if confidently detected.
 */
package golden

import cats.Monad
import cats.syntax.flatMap._

final class EvaluatesTwice[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => fa.flatMap(_ => pure(a)))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

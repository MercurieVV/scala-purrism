/*
rules = [PreferCatsFunctions]

# D4: The match only holds if the enclosing method signature is strengthened
# with a new/stronger constraint that cannot be proven safe project-wide. See
# KleisliLiftScope precedent: signature changes are whole-project decisions,
# never per-file guesses. Decline with one Warning diagnostic.
 */
package golden

import cats.Monad
import cats.syntax.functor._

final class RequiresUnsafeSignatureChange[F[_]: Monad] {
  def test(fa: F[Int]): F[String] =
    fa.map(_.toString)
}

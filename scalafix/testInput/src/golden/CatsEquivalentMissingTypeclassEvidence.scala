/*
rules = [PreferCatsFunctions]

# D3: The normalized body matches a Cats function that requires a typeclass
# constraint (§2 P8) not derivable in the enclosing scope. For example,
# foldMap requires a Monoid, but the scope only has Monad. Never introduce
# constraint requirements unsafely; decline with one Warning diagnostic.
 */
package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class MissingTypeclassEvidence[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => pure(a + 1))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

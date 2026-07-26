/*
rules = [PreferCatsFunctions]

# D2: The normalized body matches only private/internal Cats implementation
# details, with no public API of the same normalized shape. Never rewrite to
# a non-public symbol; decline with one Warning diagnostic.
 */
package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class PrivateCatsImplOnly[F[_]: Monad] {
  def test(fa: F[Int]): F[Unit] =
    fa.flatMap(_ => pure(()))

  private def pure[A](a: A)(implicit F: Monad[F]): F[A] = F.pure(a)
}

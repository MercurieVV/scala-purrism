/*
rules = [PreferCatsFunctions]

# P3: Normalized body matches a Cats function except a parameter is by-name
# in one side but strict in the other. By-name changes when/if/how-often the
# argument runs; this is not equivalent and must not be rewritten. Warning
# only if confidently detected.
 */
package golden

import cats.Monad
import cats.syntax.flatMap._

final class ByNameDiffers[F[_]: Monad] {
  def test(fa: F[Int]): F[Int] =
    fa.flatMap(a => byNameFlatMap(fa, a))

  private def byNameFlatMap[A](fa: F[A], a: A)(implicit F: Monad[F]): F[A] =
    fa.flatMap(_ => F.pure(a))
}

/*
rules = [PreferCatsFunctions]

# D1: The normalized body matches 2+ Cats functions that remain tied after all
# ranking criteria (§4). Ranking cannot resolve a unique winner, so the rule
# declines with a single Warning diagnostic and no patch.
 */
package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class AmbiguousCatsMatches[F[_]: Monad] {
  def test(fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.flatMap(a => fb.map(b => (a, b)))
}

/*
rules = [PreferCatsFunctions]

# A block that reimplements a Cats function is the same computation as the
# one-liner spelling of it, so it must be found and replaced as a whole. The
# `val` is referenced exactly once, so substituting it into its use site changes
# neither evaluation order nor evaluation count -- see BlockInliner.
 */
package golden

import cats.Applicative
import cats.Traverse
import cats.syntax.all._

final class BlockSingleUseVal[F[_]: Traverse, G[_]: Applicative] {
  def test(fga: F[G[Int]]): G[F[Int]] = {
    val sequenced = fga.traverse(x => x)
    sequenced
  }
}

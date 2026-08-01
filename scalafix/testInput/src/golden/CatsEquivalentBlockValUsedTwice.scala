/*
rules = [PreferCatsFunctions]

# P2: the `val` is referenced twice, so substituting it into its use sites would
# evaluate `fga.traverse(...)` twice where the source evaluates it once. Changing
# evaluation count changes observable behaviour with effects, so the block keeps
# its binding -- BlockInliner only inlines a single-use `val`. The binding's own
# right-hand side is still rewritten, which preserves the single evaluation.
 */
package golden

import cats.Applicative
import cats.Traverse
import cats.syntax.all._

final class BlockValUsedTwice[F[_]: Traverse, G[_]: Applicative] {
  def test(
      fga: F[G[Int]],
      combine: (G[F[Int]], G[F[Int]]) => G[F[Int]]
  ): G[F[Int]] = {
    val sequenced = fga.traverse(x => x)
    combine(sequenced, sequenced)
  }
}

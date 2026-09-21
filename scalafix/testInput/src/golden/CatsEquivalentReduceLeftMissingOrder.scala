/*
rules = [PreferCatsFunctions]

# D3: `fa.reduceLeft(cats.Order[Int].max)` structurally matches
# `Reducible#maximum`'s body (`recv.reduceLeft(Ord.max)`), which requires an
# `Order[A]` constraint (docs/PREFER_CATS_FUNCTIONS.md §2 P8). `Order[Int]` is
# summoned inline rather than threaded through an enclosing `implicit`
# parameter, so the constraint is not derivable from the declaration -- the
# rule declines with one Warning diagnostic and no patch.
 */
package golden

import cats.Reducible
import cats.syntax.all._

final class ReduceLeftMissingOrder[F[_]: Reducible] {
  def test(fa: F[Int]): Int =
    fa.reduceLeft(cats.Order[Int].max) // assert: PreferCatsFunctions.missing-typeclass-evidence
}

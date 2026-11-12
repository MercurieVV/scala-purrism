/*
rules = [TypelevelPurrism]

# Under the umbrella, `PreferTypeParameters` widens `total` and `summed` by
# reusing a type parameter already in scope (`G` of the class, `H` of the
# enclosing def) and moves their `List` uses onto it, relying on that
# parameter's Traverse bound for `foldLeft`. Weakening decides from the
# original body, where `G`/`H` only need Functor, so it would drop the bound
# the moved uses need; the bound of a reused type parameter is kept.
 */
package golden

import cats.Traverse
import cats.syntax.all.*

final class WeakenUmbrellaReusedParam[G[_]: Traverse](g: G[Int]) {
  def bumped: G[Int] = g.map(_ + 1)

  private def total(values: List[Int]): Int = values.foldLeft(0)(_ + _)
}

object WeakenUmbrellaReusedParam {
  def outer[H[_]: Traverse](h: H[Int]): H[Int] = {
    def summed(values: List[Int]): Int = values.foldLeft(0)(_ + _)
    h.map(_ + 1)
  }
}

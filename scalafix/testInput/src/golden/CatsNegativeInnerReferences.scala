/*
rules = [SimplifyCatsExpressions]

# The second effect depends on the value bound by the first, so the two are
# sequential, not independent, and mapN cannot express them. The combined
# result is not the pair of both values either, so this is not mproduct. This
# file must come back unchanged.
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsNegativeInnerReferences[F[_]: Monad] {
  def dependent(first: F[Int], render: Int => F[String]): F[String] =
    first.flatMap(value => render(value).map(label => s"$value-$label"))
}

/*
rules = [SimplifyCatsExpressions]

# The second effect depends on the value bound by the first, so the two are
# sequential, not independent. `mapN` evaluates both effects without that
# dependency and does not typecheck here. This file must come back unchanged.
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsNegativeInnerReferences[F[_]: Monad] {
  def dependent(first: F[Int], render: Int => F[String]): F[(Int, String)] =
    first.flatMap(value => render(value).map(label => (value, label)))
}

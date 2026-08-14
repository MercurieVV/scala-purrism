/*
rules = [DisableSyntax]
 */
package golden

import cats.Traverse
import cats.syntax.functor._

final class HktRewriterExistingReuse[G[_]: Traverse] {
  private def transform(values: G[Int]): G[Int] =
    values.map(identity)
}

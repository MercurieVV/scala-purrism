/*
rules = [DisableSyntax]
 */
package golden

import cats.Traverse

final class HktRewriterExistingReuse[G[_]: Traverse] {
  private def transform(values: List[Int]): List[Int] =
    values.map(identity)
}

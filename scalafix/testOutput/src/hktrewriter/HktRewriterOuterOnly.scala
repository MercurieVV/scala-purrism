/*
rules = [DisableSyntax]
 */
package golden

import cats.Functor
import cats.syntax.all.*

object HktRewriterOuterOnly {
  private def transform[G[_]: Functor](values: G[List[Int]]): G[List[Int]] =
    values.map(identity)
}

/*
rules = [DisableSyntax]
 */
package golden

import cats.Functor
import cats.syntax.all.*

object HktRewriterOuterOnly {
  private def transform(values: List[List[Int]]): List[List[Int]] =
    values.map(identity)
}

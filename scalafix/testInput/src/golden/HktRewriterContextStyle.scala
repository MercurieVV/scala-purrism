/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative

object HktRewriterStyleCases {
  private def style[F[_]: Applicative](value: F[Int]): F[Int] = value

  private def transform(values: List[Int]): List[Int] =
    values.map(identity)
}

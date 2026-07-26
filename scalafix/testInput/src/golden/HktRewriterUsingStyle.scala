/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative

object HktRewriterUsingStyleCases {
  private def style[F[_]](value: F[Int])(using Applicative[F]): F[Int] = value

  private def transform(values: List[Int]): List[Int] =
    values.map(identity)
}

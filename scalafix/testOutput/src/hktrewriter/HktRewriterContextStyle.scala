/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative
import cats.Functor
import cats.syntax.functor._

object HktRewriterStyleCases {
  private def style[F[_]: Applicative](value: F[Int]): F[Int] = value

  private def transform[G[_]: Functor](values: G[Int]): G[Int] =
    values.map(identity)
}

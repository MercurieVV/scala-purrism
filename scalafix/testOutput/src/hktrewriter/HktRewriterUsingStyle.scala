/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative
import cats.Functor
import cats.syntax.functor._

object HktRewriterUsingStyleCases {
  private def style[F[_]](value: F[Int])(using Applicative[F]): F[Int] = value

  private def transform[G[_]](values: G[Int])(using Functor[G]): G[Int] =
    values.map(identity)
}

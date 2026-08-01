/*
rules = [PreferCatsSyntax]
 */
package golden

import cats.MonadThrow

final class CatsSyntaxRaiseError[F[_]: MonadThrow] {
  def fail(error: Throwable): F[String] =
    MonadThrow[F].raiseError[String](error)
}

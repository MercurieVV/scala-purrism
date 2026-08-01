/*
rules = [PreferCatsSyntax]
 */
package golden

import cats.Applicative

final class CatsSyntaxPure[F[_]: Applicative] {
  def build(id: String): F[String] =
    Applicative[F].pure(id)
}

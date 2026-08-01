/*
rules = [PreferCatsSyntax]
 */
package golden

import cats.Functor

final class CatsSyntaxMap[F[_]: Functor] {
  def label(seed: F[Int]): F[String] =
    Functor[F].map(seed)(value => s"id-$value")
}

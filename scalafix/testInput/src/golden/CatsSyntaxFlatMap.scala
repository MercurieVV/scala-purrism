/*
rules = [PreferCatsSyntax]
 */
package golden

import cats.FlatMap

final class CatsSyntaxFlatMap[F[_]: FlatMap] {
  def next(seed: F[Int], render: Int => F[String]): F[String] =
    FlatMap[F].flatMap(seed)(value => render(value))
}

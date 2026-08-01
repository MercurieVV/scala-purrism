
package golden

import cats.FlatMap
import cats.syntax.all._

final class CatsSyntaxFlatMap[F[_]: FlatMap] {
  def next(seed: F[Int], render: Int => F[String]): F[String] =
    seed.flatMap(value => render(value))
}


package golden

import cats.Functor
import cats.syntax.all._

final class CatsSyntaxMap[F[_]: Functor] {
  def label(seed: F[Int]): F[String] =
    seed.map(value => s"id-$value")
}

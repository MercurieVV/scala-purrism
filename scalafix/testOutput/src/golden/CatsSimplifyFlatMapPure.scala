
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyFlatMapPure[F[_]: Monad] {
  def mapped(seed: F[Int]): F[String] =
    seed.map(value => value.toString)
}

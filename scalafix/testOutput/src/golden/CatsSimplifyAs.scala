
package golden

import cats.Functor
import cats.syntax.all.*

final class CatsSimplifyAs[F[_]: Functor] {
  def replace(seed: F[Int]): F[String] =
    seed.as("done")
}

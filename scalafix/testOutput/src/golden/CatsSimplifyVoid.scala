
package golden

import cats.Functor
import cats.syntax.all.*

final class CatsSimplifyVoid[F[_]: Functor] {
  def discardWithMap(seed: F[Int]): F[Unit] =
    seed.void

  def discardWithAs(seed: F[Int]): F[Unit] =
    seed.as(())
}


package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyMapThen[F[_]: Monad] {
  def bind(seed: F[Int], render: Int => F[String]): F[String] =
    seed.flatMap(render)

  def collect(ids: List[Int], load: Int => F[String]): F[List[String]] =
    ids.traverse(load)

  def discardAll(ids: List[Int], load: Int => F[String]): F[Unit] =
    ids.traverse_(load)
}

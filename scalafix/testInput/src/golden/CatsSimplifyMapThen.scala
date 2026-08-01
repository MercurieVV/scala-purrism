/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyMapThen[F[_]: Monad] {
  def bind(seed: F[Int], render: Int => F[String]): F[String] =
    seed.map(render).flatten

  def collect(ids: List[Int], load: Int => F[String]): F[List[String]] =
    ids.map(load).sequence

  def discardAll(ids: List[Int], load: Int => F[String]): F[Unit] =
    ids.map(load).sequence_
}

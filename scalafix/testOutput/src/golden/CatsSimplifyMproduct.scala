
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsSimplifyMproduct[F[_]: Monad] {
  def paired(ids: F[Int], load: Int => F[String]): F[(Int, String)] =
    ids.mproduct(load)
}

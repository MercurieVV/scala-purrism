
package golden

import cats.effect.IO
import cats.syntax.all.*

final class IdiomFoldM {
  def total(steps: List[Int]): IO[Long] =
    steps.foldM(0L)((sum, step) => IO.pure(sum + step))
}

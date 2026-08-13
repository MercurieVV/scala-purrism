/*
rules = [PreferIndexedMap]
 */
package golden

import cats.effect.IO
import cats.syntax.all.*

final class IdiomFoldM {
  def total(steps: List[Int]): IO[Long] =
    steps.foldLeft(IO.pure(0L))((acc, step) => acc.flatMap(sum => IO.pure(sum + step)))
}

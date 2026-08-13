/*
rules = [PreferIndexedMap]
 */
package golden

import cats.effect.IO
import cats.syntax.all.*

final class IdiomIdempotentIndexed {
  def numbered(rows: List[String]): List[String] =
    rows.zipWithIndex.map { case (x, i) => s"$i:${x}" }.toList

  def shouted(rows: List[String]): List[String] =
    rows.map(x => x.toUpperCase).toList

  def total(steps: List[Int]): IO[Long] =
    steps.foldM(0L)((sum, step) => IO.pure(sum + step))
}

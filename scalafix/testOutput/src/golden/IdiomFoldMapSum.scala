
package golden

import cats.syntax.all._
final class IdiomFoldMapSum {

  def totalList(rows: List[Int]): Int =
    rows.foldMap(row => row + 1)

  def totalVector(rows: Vector[Double]): Double =
    rows.foldMap(row => row * 2.0)

  def totalSeq(rows: Seq[Long]): Long =
    rows.foldMap(row => row + 1L)

  /** Cats has no `Foldable[Set]`, only `UnorderedFoldable`, which spells this
    * `unorderedFoldMap`. Rewriting this would not compile.
    */
  def totalSet(rows: Set[Int]): Int =
    rows.map(row => row + 1).sum

  /** No `Foldable[Array]` either. */
  def totalArray(rows: Array[Int]): Int =
    rows.map(row => row + 1).sum
}

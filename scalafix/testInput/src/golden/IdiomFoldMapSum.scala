/*
rules = [PreferIndexedMap]
 */
package golden

final class IdiomFoldMapSum {

  def totalList(rows: List[Int]): Int =
    rows.map(row => row + 1).sum

  def totalVector(rows: Vector[Double]): Double =
    rows.map(row => row * 2.0).sum

  def totalSeq(rows: Seq[Long]): Long =
    rows.map(row => row + 1L).sum

  /** Cats has no `Foldable[Set]`, only `UnorderedFoldable`, which spells this
    * `unorderedFoldMap`. Rewriting this would not compile.
    */
  def totalSet(rows: Set[Int]): Int =
    rows.map(row => row + 1).sum

  /** No `Foldable[Array]` either. */
  def totalArray(rows: Array[Int]): Int =
    rows.map(row => row + 1).sum
}

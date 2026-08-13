
package golden

final class IdiomZipWithIndex {
  def numbered(rows: List[String]): List[String] =
    rows.zipWithIndex.map { case (x, i) => s"$i:${x}" }.toList

  def shouted(rows: List[String]): List[String] =
    rows.map(x => x.toUpperCase).toList

  def neighbours(rows: List[String]): List[String] =
    (1 until rows.length).map(i => rows(i - 1)).toList
}

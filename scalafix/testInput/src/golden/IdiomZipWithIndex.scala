/*
rules = [PreferIndexedMap]
 */
package golden

final class IdiomZipWithIndex {
  def numbered(rows: List[String]): List[String] =
    rows.indices.map(i => s"$i:${rows(i)}").toList

  def shouted(rows: List[String]): List[String] =
    rows.indices.map(i => rows(i).toUpperCase).toList

  def neighbours(rows: List[String]): List[String] =
    (1 until rows.length).map(i => rows(i - 1)).toList
}

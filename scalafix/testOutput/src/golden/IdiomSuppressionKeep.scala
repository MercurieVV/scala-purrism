
package golden

final class IdiomSuppressionKeep {
  // purrism:keep measured in bytes per chunk, ADR-020
  def hot(rows: List[String]): List[String] =
    rows.indices.map(i => rows(i).toUpperCase).toList

  def cold(rows: List[String]): List[String] =
    rows.map(x => x.toUpperCase).toList
}

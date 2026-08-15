/*
rules = [PreferPolymorphicCollections]
 */
package golden

final class ContainerIndexedDecline {
  private def first(rows: List[String]): String =
    rows.head // assert: PreferPolymorphicCollections

  private def at(rows: List[String], i: Int): String =
    rows(i) // assert: PreferPolymorphicCollections
}

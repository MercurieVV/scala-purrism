/*
rules = [PreferPolymorphicCollections]

PreferPolymorphicCollections.maxConstraints = 1
 */
package golden

final class ContainerTooManyConstraintsDecline {
  // `map` then `filter` needs two independent capabilities; with
  // `maxConstraints = 1` the solver has no single-constraint candidate to
  // offer and declines.
  private def positive(rows: List[Int]): List[Int] = // assert: PreferPolymorphicCollections.too-many-constraints
    rows.map(row => row + 1).filter(row => row > 0)
}


package golden

final class ContainerTooManyConstraintsDecline {
  // `map` then `filter` needs two independent capabilities; with
  // `maxConstraints = 1` the solver has no single-constraint candidate to
  // offer and declines.
  private def positive(rows: List[Int]): List[Int] = 
    rows.map(row => row + 1).filter(row => row > 0)
}

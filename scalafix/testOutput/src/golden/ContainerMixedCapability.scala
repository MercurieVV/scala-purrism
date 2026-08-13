
package golden

import cats.UnorderedFoldable
import cats.syntax.unorderedFoldable.*

/** A body that transforms and then narrows. Two capabilities, not one. */
final class ContainerMixedCapability {
  private def positive(rows: List[Int]): List[Int] = 
    rows.map(row => row + 1).filter(row => row > 0)

  private def anyPositive[S[_]: UnorderedFoldable](rows: S[Int]): Boolean =
    rows.exists(row => row > 0)
}

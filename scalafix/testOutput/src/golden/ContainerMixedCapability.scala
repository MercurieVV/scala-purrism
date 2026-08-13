
package golden

import cats.{Functor, FunctorFilter, UnorderedFoldable}
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.unorderedFoldable._
/** A body that transforms and then narrows. Two capabilities, not one. */
final class ContainerMixedCapability {
  private def positive[S[_]: Functor: FunctorFilter](rows: S[Int]): S[Int] =
    rows.map(row => row + 1).filter(row => row > 0)

  private def onlyFilter[S[_]: FunctorFilter](rows: S[Int]): S[Int] =
    rows.filter(row => row > 0)

  private def anyPositive[S[_]: UnorderedFoldable](rows: S[Int]): Boolean =
    rows.exists(row => row > 0)
}


package golden

import cats.Foldable
import cats.syntax.foldable._
final class ContainerExitToList {

  /** `toList` leaves the container: everything after it is a `List` for every
    * `S`, so `zipWithIndex` does not stop the widening.
    */
  private def numbered[T, S[_]: Foldable](rows: S[(T, T)], base: Int): List[(Int, T)] =
    rows.toList.zipWithIndex.map { case ((_, value), index) =>
      (base + index, value)
    }
}

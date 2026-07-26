/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractMissingCatsEvidence {
  private def total[B](xs: List[B]): B = {
    implicit def monoidB: cats.Monoid[B] = sys.error("stub")
    import cats.syntax.foldable._
    xs.foldMap(identity) // assert: PreferHKTTypeclasses
  }
}

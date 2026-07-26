/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractMissingCatsEvidence {
  private def total[B](xs: List[B]): B = { // assert: PreferHKTTypeclasses
    implicit def monoidB: cats.Monoid[B] = sys.error("stub") // assert: PreferHKTTypeclasses
    import cats.syntax.foldable._
    xs.foldMap(identity)
  }
}

/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractConcretePatternMatch {
  private def count(xs: List[Int]): Int =
    xs match {
      case Nil => 0 // assert: PreferHKTTypeclasses
      case h :: t => 1 + count(t)
    }
}

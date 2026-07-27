/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractConcretePatternMatch {
  private def count(xs: List[Int]): Int =
    xs match { // assert: PreferHKTTypeclasses
      case Nil => 0
      case h :: t => 1 + count(t)
    }
}

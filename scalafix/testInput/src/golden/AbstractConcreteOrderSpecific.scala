/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractConcreteOrderSpecific {
  private def first(xs: List[Int]): Int =
    xs.sorted.head // assert: PreferHKTTypeclasses
}

/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

object AbstractAmbiguousWeakestCapability {
  private def sum(xs: List[Int]): Int =
    xs.reduce(_ + _) // assert: PreferPolymorphicTypeclasses
}

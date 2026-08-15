/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

object AbstractMutableOrThrowingBody {
  private def accumulate(xs: List[Int]): Int = {
    var acc = 0 // assert: PreferPolymorphicTypeclasses
    for (x <- xs) acc = acc + x
    acc
  }
}

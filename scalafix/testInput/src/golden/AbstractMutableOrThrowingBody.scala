/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractMutableOrThrowingBody {
  private def accumulate(xs: List[Int]): Int = {
    var acc = 0 // assert: PreferHKTTypeclasses
    for (x <- xs) acc = acc + x
    acc
  }
}

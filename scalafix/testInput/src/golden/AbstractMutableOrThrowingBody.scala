/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractMutableOrThrowingBody {
  private def accumulate(xs: List[Int]): Int = { // assert: PreferHKTTypeclasses
    var acc = 0
    for (x <- xs) acc = acc + x
    acc
  }
}

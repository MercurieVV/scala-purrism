/*
rules = [PreferHKTTypeclasses]
 */
package golden

object AbstractTypeParamNameConflict {
  def process[G, H, K](xs: List[Int]): List[String] = // assert: PreferHKTTypeclasses
    xs.map(_.toString)
}


package golden

object AbstractTypeParamNameConflict {
  def process[G, H, K](xs: List[Int]): List[String] =
    xs.map(_.toString) 
}

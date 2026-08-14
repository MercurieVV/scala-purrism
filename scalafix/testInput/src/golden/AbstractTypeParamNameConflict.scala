/*
rules = [PreferHKTTypeclasses]

PreferHKTTypeclasses.widenPublic = true

# The collections are `PreferContainerTypeclasses`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferHKTTypeclasses.containers = []
 */
package golden

object AbstractTypeParamNameConflict {
  def process[G, H, K](xs: List[Int]): List[String] = // assert: PreferHKTTypeclasses
    xs.map(_.toString)
}

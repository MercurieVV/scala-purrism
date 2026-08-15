/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = true

# The collections are `PreferPolymorphicCollections`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferPolymorphicTypeclasses.containers = []
 */
package golden

object AbstractTypeParamNameConflict {
  def process[G, H, K](xs: List[Int]): List[String] = // assert: PreferPolymorphicTypeclasses
    xs.map(_.toString)
}

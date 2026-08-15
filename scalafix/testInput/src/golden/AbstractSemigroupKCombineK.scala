/*
rules = [PreferPolymorphicTypeclasses]

# The collections are `PreferPolymorphicCollections`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferPolymorphicTypeclasses.containers = []
 */
package golden

import cats.syntax.semigroupk._

object AbstractSemigroupKCombineK {
  private def combine(x: List[Int], y: List[Int]): List[Int] = x <+> y
}

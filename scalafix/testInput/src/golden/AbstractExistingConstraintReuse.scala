/*
rules = [PreferHKTTypeclasses]

# The collections are `PreferContainerTypeclasses`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferHKTTypeclasses.containers = []
 */
package golden

import cats.Traverse

class AbstractExistingConstraintReuse[G[_]: Traverse] {
  private case class User(name: String)

  private def names(us: List[User]): List[String] = us.map(_.name)
}

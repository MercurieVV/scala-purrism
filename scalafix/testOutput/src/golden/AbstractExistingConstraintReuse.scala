
package golden

import cats.Traverse
import cats.syntax.functor.*

class AbstractExistingConstraintReuse[G[_]: Traverse] {
  private case class User(name: String)

  private def names(us: G[User]): G[String] = us.map(_.name)
}

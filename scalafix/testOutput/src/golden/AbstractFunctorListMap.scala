
package golden

import cats.Functor
import cats.syntax.functor._

case class User(name: String)

object AbstractFunctorListMap {
  private def names[G[_]: Functor](us: G[User]): G[String] = us.map(_.name)
}

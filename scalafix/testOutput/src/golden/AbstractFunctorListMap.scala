
package golden

import cats.syntax.functor._
import cats.Functor

case class User(name: String)

object AbstractFunctorListMap {
  private def names[G[_]: Functor](us: G[User]): G[String] = us.map(_.name)
}

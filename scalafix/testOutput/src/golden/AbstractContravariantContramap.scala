
package golden

import cats.Contravariant
import cats.syntax.contravariant.*

object AbstractContravariantContramap {
  private case class User(name: String)

  private def byName[G[_]: Contravariant](s: G[String]): G[User] = s.contramap(_.name)
}

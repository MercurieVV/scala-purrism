
package golden

import cats.syntax.contravariant.*
import cats.Contravariant

object AbstractContravariantContramap {
  private case class User(name: String)

  private def byName[G[_]: Contravariant](s: G[String]): G[User] = s.contramap(_.name)
}

/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Show
import cats.syntax.contravariant.*

object AbstractContravariantContramap {
  private case class User(name: String)

  private def byName(s: Show[String]): Show[User] = s.contramap(_.name)
}

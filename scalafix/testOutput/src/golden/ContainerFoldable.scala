
package golden

import cats.Functor
import cats.syntax.functor.*

final class ContainerFoldable {
  private def names[S[_]: Functor](users: S[String]): S[String] =
    users.map(user => user.toUpperCase)

  def render(users: List[String]): List[String] = names(users)
}

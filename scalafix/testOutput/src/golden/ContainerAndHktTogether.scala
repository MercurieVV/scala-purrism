
package golden

import cats.Functor
import cats.syntax.functor._
final class ContainerAndHktTogether {
  private def names[S[_]: Functor](users: S[String]): S[String] =
    users.map(user => user.toUpperCase)
}

package golden

import cats.Traverse
import cats.syntax.functor._

class UserProcessor[G[_]: Traverse] {
  private case class User(name: String)

  private def names(us: G[User]): G[String] = us.map(_.name)
}

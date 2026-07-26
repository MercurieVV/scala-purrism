package golden

import cats.Traverse

class UserProcessor[G[_]: Traverse] {
  private case class User(name: String)

  private def names(us: List[User]): List[String] = us.map(_.name)
}

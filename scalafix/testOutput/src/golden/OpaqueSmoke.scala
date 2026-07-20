opaque type UserId = String
object UserId:
  def apply(value: String): UserId = value.asInstanceOf[UserId]
  extension (opaqueValue: UserId) def value: String = opaqueValue.asInstanceOf[String]
  given cats.Eq[UserId] = cats.Eq.by(_.value)

case class User(userId: UserId, name: String)

class UserRepository {
  def findById(userId: UserId): Option[User] = None
}

class UserService(repo: UserRepository) {
  def processUser(userId: UserId): Unit = {
    repo.findById(userId)
    ()
  }
}

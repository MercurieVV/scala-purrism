opaque type UserId = String
object UserId:
  def apply(value: String): UserId = value
  extension (self: UserId) def value: String = self

case class User(userId: UserId, name: String)

class UserRepository {
  def findById(userId: String): Option[User] = None
}

class UserService(repo: UserRepository) {
  def processUser(userId: String): Unit = {
    repo.findById(userId)
    ()
  }
}

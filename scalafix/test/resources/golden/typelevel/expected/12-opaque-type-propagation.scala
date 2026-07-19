package golden.typelevel

opaque type UserId = String
object UserId:
  def apply(value: String): UserId = value
  extension (opaqueValue: UserId) def value: String = opaqueValue

case class User(userId: UserId, name: String)

class UserRepository {
  def findById(userId: UserId): Option[User] = None
}

class UserService(repo: UserRepository) {
  def processUser(userId: UserId): Unit = {
    repo.findById(userId)
  }
}

package golden.typelevel

case class User(userId: String, name: String)

class UserRepository {
  def findById(userId: String): Option[User] = None
}

class UserService(repo: UserRepository) {
  def processUser(userId: String): Unit = {
    repo.findById(userId)
  }
}

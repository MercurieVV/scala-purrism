/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "UserId"
    underlying = "scala/Predef.String#"
    definitionFile = "scalafix/testInput/src/golden/OpaqueSmoke.scala"
    seeds = [ "_empty_/User#userId." ]
  }
]

# Smoke test for the semantic fixture harness itself: proves a rule really runs
# against a compiled SemanticDocument and that its output is diffed against
# testOutput.
#
# Deliberately has NO package clause, so the value's owner resolves under
# `_empty_` rather than a named package.
 */
case class User(userId: String, name: String)

class UserRepository {
  def findById(userId: String): Option[User] = None
}

class UserService(repo: UserRepository) {
  def processUser(userId: String): Unit = {
    repo.findById(userId)
    ()
  }
}

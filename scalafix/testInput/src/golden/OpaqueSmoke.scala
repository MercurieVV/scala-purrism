/*
rules = [OpaqueTypePropagation]

# Smoke test for the semantic fixture harness itself: proves a rule really runs
# against a compiled SemanticDocument and that its output is diffed against
# testOutput.
#
# Deliberately has NO package clause. OpaqueTypePropagation's insertTypeDefs
# (OpaqueTypePropagation.scala:346) reads `source.stats`, whose head is the Pkg
# node, so with a package clause it emits the opaque type *before* `package foo`,
# which does not compile. That rule is frozen; PropagateOpaqueType must place
# definitions inside the package.
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

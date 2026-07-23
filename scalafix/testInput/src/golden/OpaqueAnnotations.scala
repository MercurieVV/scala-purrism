/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "BranchName"
    underlying = "scala/Predef.String#"
    seeds = [ "golden/Branch#name." ]
  }
]

# Stage 2 covers type annotations only, so this fixture deliberately creates no
# BranchName anywhere: every value is passed along from the seed field. That
# keeps both the input and the expected output compilable while wrapping and
# unwrapping are still unimplemented.
 */
package golden

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (branch: BranchName) def value: String = branch

final case class Branch(name: String, description: String)

final class Branches {

  def show(branchName: String): String = branchName

  def relay(branch: Branch): String = show(branch.name)

  // `description` is a String on the same case class and must be left alone.
  def describe(branch: Branch): String = branch.description
}

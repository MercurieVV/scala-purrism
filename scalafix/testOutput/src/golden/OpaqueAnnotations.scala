package golden

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (branch: BranchName) def value: String = branch

final case class Branch(name: BranchName, description: String)

final class Branches {

  def show(branchName: BranchName): BranchName = branchName

  def relay(branch: Branch): BranchName = show(branch.name)

  // `description` is a String on the same case class and must be left alone.
  def describe(branch: Branch): String = branch.description
}

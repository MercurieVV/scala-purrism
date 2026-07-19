package golden.typelevel

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (opaqueValue: BranchName) def value: String = opaqueValue
  given cats.Eq[BranchName] = cats.Eq.by(_.value)

final class BranchWorkflow[F[_]]:
  def create(branchName: BranchName): Unit = ()
  def load(branchName: F[BranchName]): F[BranchName] = branchName
  def known(branchName: List[BranchName]): List[BranchName] = branchName

package golden.typelevel

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (opaqueValue: BranchName) def value: String = opaqueValue
  given cats.Eq[BranchName] = cats.Eq.by(_.value)

final case class PullRequest(baseRefName: BranchName)

final class MergeWorkflow:
  def awaitBranch(baseRefName: BranchName): Unit = ()

  def isDefault(merged: PullRequest): Boolean =
    merged.baseRefName === BranchName("master")

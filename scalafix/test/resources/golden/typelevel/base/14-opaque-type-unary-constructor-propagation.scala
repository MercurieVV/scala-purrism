package golden.typelevel

final class BranchWorkflow[F[_]]:
  def create(branchName: String): Unit = ()
  def load(branchName: F[String]): F[String] = branchName
  def known(branchName: List[String]): List[String] = branchName

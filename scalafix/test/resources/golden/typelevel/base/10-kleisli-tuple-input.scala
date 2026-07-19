package examples

import cats.Monad

final class GitChecks[F[_]: Monad] {
  def ensureBranch(root: os.Path, branchName: String): F[Boolean] =
    branchExistsLocally(root, branchName)

  private def branchExistsLocally(
      root: os.Path,
      branchName: String
  ): F[Boolean] =
    F.blocking {
      os.proc("git", "rev-parse", "--verify", branchName)
        .call(cwd = root, stdout = os.Pipe, stderr = os.Pipe, check = false)
        .exitCode == 0
    }
}

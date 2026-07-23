package examples

import cats.Monad
import cats.data.Kleisli

final class GitChecks[F[_]: Monad] {
  def ensureBranch(root: os.Path, branchName: String): F[Boolean] =
    branchExistsLocally((root, branchName))

  private def branchExistsLocally: Kleisli[F, (os.Path, String), Boolean] =
    Kleisli.apply { case (root, branchName) =>
      F.blocking {
        os.proc("git", "rev-parse", "--verify", branchName)
          .call(cwd = root, stdout = os.Pipe, stderr = os.Pipe, check = false)
          .exitCode == 0
      }
    }
}

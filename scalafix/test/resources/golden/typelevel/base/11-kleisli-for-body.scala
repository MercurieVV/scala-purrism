package examples

import cats.Monad

final class GitCleanup[F[_]: Monad] {
  def cleanup(root: os.Path, worktreePath: os.Path, branchName: String): F[Unit] =
    releaseWorktree(root, worktreePath, branchName)

  def releaseWorktree(
      root: os.Path,
      worktreePath: os.Path,
      branchName: String
  ): F[Unit] =
    for
      _ <- progress(s"Returning to project root at $root")
      _ <- call(root, "git", "status", "--short").void
      _ <- F.blocking(os.exists(worktreePath)).flatMap {
        case false => F.unit
        case true =>
          progress(s"Removing worktree at $worktreePath") *>
            call(root, "git", "worktree", "remove", "--force", worktreePath.toString)
      }
      _ <- call(root, "git", "branch", "-D", branchName).attempt.void
    yield ()

  private def progress(message: String): F[Unit] =
    F.unit

  private def call(cwd: os.Path, command: String*): F[Unit] =
    F.unit
}

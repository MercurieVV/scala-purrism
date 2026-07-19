package examples

import cats.Monad
import cats.data.Kleisli
import cats.syntax.all.*

final class GitWorktree[F[_]: Monad] {
  def acquireWorktree
      : Kleisli[F, (os.Path, os.Path, String, Option[String], String => F[Unit]), Unit] =
    Kleisli.apply {
      case input @ (root, worktreePath, branchName, baseBranch, progress) =>
        exists(worktreePath).flatMap {
          case true =>
            progress("cleanup") *>
              (releaseWorktree.local { case (root, worktreePath, branchName, _, progress) =>
                (root, worktreePath, branchName, progress)
              } *> acquireWorktree)(input)
          case false =>
            baseBranch.traverse_(progress)
        }
    }

  def releaseWorktree
      : Kleisli[F, (os.Path, os.Path, String, String => F[Unit]), Unit] =
    Kleisli.apply { case (_, _, branchName, progress) =>
      progress(branchName)
    }

  private def exists(path: os.Path): F[Boolean] =
    false.pure[F]
}

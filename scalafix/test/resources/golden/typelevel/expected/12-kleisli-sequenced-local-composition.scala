package examples

import cats.Monad
import cats.data.Kleisli
import cats.effect.kernel.Sync
import cats.syntax.all.*

final class GitWorktree[F[_]: Monad] {
  def acquireWorktree
      : Kleisli[F, (os.Path, os.Path, String, Option[String], String => F[Unit]), Unit] =
    Kleisli.apply {
      case input @ (root, worktreePath, branchName, baseBranch, progress) =>
        exists(worktreePath).flatMap {
          case true =>
            progress("cleanup") *>
              (releaseWorktree.local[
                (os.Path, os.Path, String, Option[String], String => F[Unit])
              ] { case (root: os.Path, worktreePath: os.Path, branchName: String, _: Option[String], progress: String => F[Unit]) =>
                (root, worktreePath, branchName, progress)
              } *> archiveWorktree.local[
                (os.Path, os.Path, String, Option[String], String => F[Unit])
              ] { case (root: os.Path, worktreePath: os.Path, branchName: String, _: Option[String], _: String => F[Unit]) =>
                (root, worktreePath, branchName)
              } *> acquireWorktree).run(input)
          case false =>
            baseBranch.traverse_(progress)
        }
    }

  def releaseWorktree
      : Kleisli[F, (os.Path, os.Path, String, String => F[Unit]), Unit] =
    Kleisli.apply { case (_, _, branchName, progress) =>
      progress(branchName)
    }

  def archiveWorktree: Kleisli[F, (os.Path, os.Path, String), Unit] =
    Kleisli.apply { case (_, _, branchName) =>
      progressArchive(branchName)
    }

  private def exists(path: os.Path): F[Boolean] =
    false.pure[F]

  private def progressArchive(branchName: String): F[Unit] =
    ().pure[F]
}

final class GitTargetShape[F[_]](using F: Sync[F]):
  def acquireWorktree: Kleisli[
    F,
    (os.Path, os.Path, String, Option[String], String => F[Unit]),
    Unit
  ] =
    Kleisli.apply {
      case input @ (root, worktreePath, branchName, baseBranch, progress) =>
        F.blocking(os.exists(worktreePath)).flatMap {
          case true =>
            progress(
              s"Leftover worktree detected at $worktreePath. Cleaning up..."
            ) *> (releaseWorktree.local[
              (os.Path, os.Path, String, Option[String], String => F[Unit])
            ] { case (root: os.Path, worktreePath: os.Path, branchName: String, _: Option[String], progress: String => F[Unit]) =>
              (root, worktreePath, branchName, progress)
            } *> acquireWorktree).run(input)
          case false =>
            baseBranch.traverse_(progress)
        }
    }

  def releaseWorktree
      : Kleisli[F, (os.Path, os.Path, String, String => F[Unit]), Unit] =
    Kleisli.apply { case (_, _, branchName, progress) =>
      progress(branchName)
    }

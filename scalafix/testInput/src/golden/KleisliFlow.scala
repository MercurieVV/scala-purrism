/*
rules = [DisableSyntax]

# A graph-shape fixture, not a rewrite fixture. DisableSyntax is configured with
# nothing here so it changes no text, which is why this file needs no matching
# testOutput entry. It exists so GraphBuilderSuite can assert Kleisli tuple
# modelling against real compiler output instead of against an external repo.
#
# Mirrors Git.scala in gh-tasks-llm-executor: branchName sits at input tuple
# slot 2 of a 4-tuple, baseBranch at slot 3, and the Kleisli is applied both
# with an explicit tuple and with loose auto-tupled arguments.
 */
package golden

import cats.data.Kleisli

final class Worktrees[F[_]] {

  def acquire
      : Kleisli[F, (String, String, String, Option[String]), Unit] = ???

  def branchExists: Kleisli[F, (String, String), Boolean] = ???

  def release: Kleisli[F, (String, String, String), Unit] = ???
}

final case class Run(root: String, worktree: String, branchName: String)

/** Mirrors Git.scala:18-29: a 4-tuple input reshaped down to a 3-tuple. */
final class Reshaper[F[_]](worktrees: Worktrees[F]) {

  def reshaped: Kleisli[F, (String, String, String, Option[String]), Unit] =
    worktrees.release.local[(String, String, String, Option[String])] {
      case (
            root: String,
            worktree: String,
            branchName: String,
            _: Option[String]
          ) =>
        (root, worktree, branchName)
    }
}

/** Payload threaded through a container by a named binder and by a placeholder. */
final class Passthrough {

  def qualify(branchName: String, remote: String): String =
    s"$remote/$branchName"

  def named(base: Option[String]): Option[String] =
    base.map(branchName => qualify(branchName, "origin"))

  def placeholder(base: Option[String]): Option[String] =
    base.map(qualify(_, "origin"))
}

final class Caller[F[_]](worktrees: Worktrees[F]) {

  // Loose arguments, auto-tupled into the Kleisli's single input.
  def acquireFor(run: Run, base: Option[String]): F[Unit] =
    worktrees.acquire(run.root, run.worktree, run.branchName, base)

  // An explicit tuple.
  def existsFor(run: Run): F[Boolean] =
    worktrees.branchExists((run.root, run.branchName))

  def releaseFor(run: Run): F[Unit] =
    worktrees.release(run.root, run.worktree, run.branchName)
}

package golden.typelevel

import cats.data.Kleisli
import cats.syntax.all.*

final class BranchWorkflow[F[_]]:
  def normalize(branchName: F[String]): F[String] = fetchRaw

  def fetchRaw: F[String] = ???

  def acquire: Kleisli[F, (os.Path, String, Option[String], String => F[Unit]), Unit] =
    Kleisli.apply { case (root, branchName, baseBranch, progress) =>
      baseBranch.traverse_(ensureBranch(root, _))
    }

  def ensureBranch(root: os.Path, baseBranch: String): Unit =
    ()

  private def branchExistsLocally: Kleisli[F, (os.Path, String), Boolean] =
    Kleisli.apply { case (_, branchName) =>
      branchName == "master"
    }

  def compare(baseRefName: String): Boolean =
    baseRefName === "main"

  def select(branchName: String): List[String] =
    val branchNames = List("main", branchName)
    branchNames

  def command(branchName: String): Seq[String] =
    Seq("gh", "pr", "create", "--head", branchName)

  def helperCommand(branchName: String): Unit =
    call("git", "branch", branchName)

  def call(args: String*): Unit = ()

final case class AppInput(taskNumber: Option[Int])
final case class RunContext(taskNumber: Option[Int])
final case class Task(number: Int)

final class TaskWorkflow:
  def parseTaskNumber(args: List[String]): Option[Int] =
    args.headOption.flatMap(_.toIntOption)

  def select(input: AppInput, task: Task): Boolean =
    input.taskNumber.forall(_ === task.number)

  def waitUntil(started: Long, timeoutMillis: Long): Unit =
    loop(started + timeoutMillis)

  def loop(deadlineMillis: Long): Unit = ()

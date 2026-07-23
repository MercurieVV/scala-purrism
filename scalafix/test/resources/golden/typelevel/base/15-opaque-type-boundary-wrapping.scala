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
final case class Issue(value: Int)
final case class TaskRun(branchName: String, baseBranch: Option[String])

final class TaskWorkflow:
  private val TaskWaitMillis =
    5L

  def parseTaskNumber(args: List[String]): Option[Int] =
    args.headOption.flatMap(_.toIntOption)

  def select(input: AppInput, task: Task): Boolean =
    input.taskNumber.forall(_ === task.number)

  def waitUntil(started: Long, timeoutMillis: Long): Unit =
    loop(started + timeoutMillis)

  def loop(deadlineMillis: Long): Unit = ()

  def primitiveLong(value: Long): Unit = ()

  def passPrimitive(started: Long, timeoutMillis: Long): Unit =
    primitiveLong(timeoutMillis)

  def issue(taskId: Int): Issue =
    Issue(taskId)

  def claim(taskNumber: Int): Unit = ()

  def claimTask(task: Task): Unit =
    claim(task.number)

  def claimF[F[_]](taskNumber: Int): Unit = ()

  def claimTaskF[F[_]](task: Task): Unit =
    claimF[F](task.number)

  def waitConstant(started: Long): Unit =
    loop(started + TaskWaitMillis)

  def run(taskNumber: Int): TaskRun =
    TaskRun(
      branchName = s"task-$taskNumber",
      baseBranch = Option(taskNumber).map(parentId => s"task-$parentId")
    )

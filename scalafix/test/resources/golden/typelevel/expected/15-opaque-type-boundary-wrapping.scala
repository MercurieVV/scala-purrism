package golden.typelevel

import cats.data.Kleisli
import cats.syntax.all.*

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (opaqueValue: BranchName) def value: String = opaqueValue
  given cats.Eq[BranchName] = cats.Eq.by(_.value)

opaque type TaskNumber = Int
object TaskNumber:
  def apply(value: Int): TaskNumber = value
  extension (opaqueValue: TaskNumber) def value: Int = opaqueValue
  given cats.Eq[TaskNumber] = cats.Eq.by(_.value)

final class BranchWorkflow[F[_]]:
  def normalize(branchName: F[BranchName]): F[BranchName] =
    fetchRaw.map(BranchName(_))

  def fetchRaw: F[String] = ???

  def acquire: Kleisli[F, (os.Path, BranchName, Option[BranchName], String => F[Unit]), Unit] =
    Kleisli.apply { case (root, branchName, baseBranch, progress) =>
      baseBranch.traverse_(ensureBranch(root, _))
    }

  def ensureBranch(root: os.Path, baseBranch: BranchName): Unit =
    ()

  private def branchExistsLocally: Kleisli[F, (os.Path, BranchName), Boolean] =
    Kleisli.apply { case (_, branchName) =>
      branchName == BranchName("master")
    }

  def compare(baseRefName: BranchName): Boolean =
    baseRefName === BranchName("main")

  def select(branchName: BranchName): List[BranchName] =
    val branchNames = List(BranchName("main"), branchName)
    branchNames

  def command(branchName: BranchName): Seq[String] =
    Seq("gh", "pr", "create", "--head", branchName.value)

  def helperCommand(branchName: BranchName): Unit =
    call("git", "branch", branchName.value)

  def call(args: String*): Unit = ()

final case class AppInput(taskNumber: Option[TaskNumber])
final case class RunContext(taskNumber: Option[TaskNumber])
final case class Task(number: Int)
final case class Issue(value: Int)
final case class TaskRun(branchName: BranchName, baseBranch: Option[BranchName])

final class TaskWorkflow:
  private val TaskWaitMillis =
    DeadlineMillis(5L)

  def parseTaskNumber(args: List[String]): Option[TaskNumber] =
    args.headOption.flatMap(_.toIntOption).map(TaskNumber(_))

  def select(input: AppInput, task: Task): Boolean =
    input.taskNumber.forall(_ === TaskNumber(task.number))

  def waitUntil(started: Long, timeoutMillis: DeadlineMillis): Unit =
    loop(DeadlineMillis(started + timeoutMillis.value))

  def loop(deadlineMillis: DeadlineMillis): Unit = ()

  def primitiveLong(value: Long): Unit = ()

  def passPrimitive(started: Long, timeoutMillis: DeadlineMillis): Unit =
    primitiveLong(timeoutMillis.value)

  def issue(taskId: TaskNumber): Issue =
    Issue(taskId.value)

  def claim(taskNumber: TaskNumber): Unit = ()

  def claimTask(task: Task): Unit =
    claim(TaskNumber(task.number))

  def claimF[F[_]](taskNumber: TaskNumber): Unit = ()

  def claimTaskF[F[_]](task: Task): Unit =
    claimF[F](TaskNumber(task.number))

  def waitConstant(started: Long): Unit =
    loop(DeadlineMillis(started + TaskWaitMillis.value))

  def run(taskNumber: TaskNumber): TaskRun =
    TaskRun(
      branchName = BranchName(s"task-$taskNumber"),
      baseBranch = Option(taskNumber).map(parentId => BranchName(s"task-$parentId"))
    )

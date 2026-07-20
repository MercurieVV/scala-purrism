package golden.typelevel

opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (opaqueValue: BranchName) def value: String = opaqueValue
  given cats.Eq[BranchName] = cats.Eq.by(_.value)

final class GenericBranchWorkflow:
  def carry[D, E](d: D, e: E): E = e
  def carryBox[G[_], E](e: G[E]): G[E] = e

  final case class Box[A](value: A)

  def normalize(branchName: BranchName): BranchName =
    val selected: BranchName = carry[Any, BranchName]((), branchName)
    selected

  def normalizeBox(branchName: Box[BranchName]): Box[BranchName] =
    val selected: Box[BranchName] = carryBox[Box, BranchName](branchName)
    selected

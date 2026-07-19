package golden.typelevel

final class GenericBranchWorkflow:
  def carry[D, E](d: D, e: E): E = e
  def carryBox[G[_], E](e: G[E]): G[E] = e

  final case class Box[A](value: A)

  def normalize(branchName: String): String =
    val selected: String = carry[Any, String]((), branchName)
    selected

  def normalizeBox(branchName: Box[String]): Box[String] =
    val selected: Box[String] = carryBox[Box, String](branchName)
    selected

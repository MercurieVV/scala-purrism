package fix.hkt

import scala.meta.Tree

import scalafix.v1.Patch

/** A rule's patch together with the type parameters it builds on.
  *
  * `HktRewriter` either reuses a type parameter already in scope -- relying on
  * its bound as written to cover the uses it moves onto it -- or appends one
  * after a def's last type parameter. Under the umbrella, `TypeclassWeakening`
  * decides from the original body, so it must leave the bound of each of those
  * parameters alone: weakening a reused one drops what the moved uses need, and
  * replacing the last one edits the token the addition anchors on.
  */
private[fix] final case class Planned(patch: Patch, keptParams: List[Tree]) {
  def +(other: Planned): Planned =
    Planned(patch + other.patch, keptParams ++ other.keptParams)

  def +(other: Patch): Planned = copy(patch = patch + other)
}

private[fix] object Planned {
  val empty: Planned = Planned(Patch.empty, Nil)

  def apply(patch: Patch): Planned = Planned(patch, Nil)

  def all(plans: Iterable[Planned]): Planned = plans.foldLeft(empty)(_ + _)
}

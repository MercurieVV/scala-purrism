package fix.hkt

import scalafix.v1.Symbol

/** Kind of the type constructor under analysis. */
sealed trait KindShape

object KindShape {
  case object Star extends KindShape
  case object Unary extends KindShape
  case object Binary extends KindShape

  def arity(shape: KindShape): Int = shape match {
    case Star   => 0
    case Unary  => 1
    case Binary => 2
  }

  def parse(token: String): Option[KindShape] = token match {
    case "Star"   => Some(Star)
    case "Unary"  => Some(Unary)
    case "Binary" => Some(Binary)
    case _        => None
  }

  def render(shape: KindShape): String = shape match {
    case Star   => "Star"
    case Unary  => "Unary"
    case Binary => "Binary"
  }
}

final case class Capability(
    typeclass: Symbol,
    method: Symbol,
    owner: Symbol,
    kind: KindShape,
    derived: Boolean,
    arity: Int
)

sealed trait StdlibMapping

object StdlibMapping {
  final case class ToCapability(owner: Symbol, method: Symbol)
      extends StdlibMapping
  final case class ToDecline(reason: String) extends StdlibMapping
}

final case class StdlibEntry(
    concreteMethod: Symbol,
    mapping: StdlibMapping
)

/** A stdlib operation whose Cats counterpart is spelled differently and takes
  * its meaning from a typeclass on the *element*.
  *
  * `xs.mkString(p, d, s)` becomes `xs.mkString_(p, d, s)`, which needs
  * `Foldable[S]` like any other container operation, but also `Show[A]` -- and
  * `Show` is not `toString`. That makes the rewrite a change of behaviour
  * wherever the two disagree, which is why these rules are read by a separate
  * rule rather than folded into the container one.
  *
  * `capabilityOwner`/`capabilityMethod` are the container capability the
  * operation still needs, so the ordinary solver can account for it.
  */
final case class ElementRule(
    concreteMethod: Symbol,
    renameTo: String,
    capabilityOwner: Symbol,
    capabilityMethod: Symbol,
    elementConstraint: Symbol
)

final case class CatsTypeclass(
    symbol: Symbol,
    parents: List[Symbol],
    kind: KindShape,
    typeParamCount: Int,
    depth: Int,
    renderName: String,
    importPath: String,
    isPublic: Boolean
)

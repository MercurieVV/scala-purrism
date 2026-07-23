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

package fix.vocabulary

import scala.meta._
import scalafix.v1._

final case class WhitelistViolation(
    position: scala.meta.inputs.Position,
    foundFqcn: String
)

object TypeWhitelistCheck {

  /** `Either[String, Int]`'s type arguments (`String`, `Int`) are not
    * separately named references per the spec's grammar -- only the applied
    * type's own head (`Either`) is a "named type". A node counts as an
    * argument, and is skipped, if any ancestor in its parent chain is a
    * `Type.ArgClause` -- regardless of nesting depth, so an argument of an
    * argument is still skipped.
    */
  private def insideArgClause(node: Tree): Boolean = {
    @scala.annotation.tailrec
    def loop(t: Tree): Boolean = t.parent match {
      case Some(_: Type.ArgClause) => true
      case Some(p)                 => loop(p)
      case None                    => false
    }
    loop(node)
  }

  /** `type Error = String` binds a concrete implementation to an abstract type
    * member -- a type-alias binding, not a value-bearing member type, so it
    * gets the same exemption as the abstract member it implements.
    */
  private def isTypeAliasBinding(node: Tree): Boolean =
    node.parent.exists {
      case dt: Defn.Type => dt.body eq node
      case _             => false
    }

  private def namedTypesIn(tree: Tree): List[Type] =
    tree
      .collect {
        case t: Type.Name   => t
        case t: Type.Select => t
      }
      .filterNot(t => insideArgClause(t) || isTypeAliasBinding(t))

  def violations(tree: Tree, allowed: PatternList)(implicit
      doc: SemanticDocument
  ): List[WhitelistViolation] =
    namedTypesIn(tree).flatMap { tpe =>
      val symbol = tpe.symbol
      if (symbol == Symbol.None) Nil
      else {
        val info = symbol.info
        val exempt =
          info.exists(i => i.isTypeParameter || (i.isType && i.isAbstract))
        if (exempt) Nil
        else {
          val fqcn = PatternList.normalize(symbol.value)
          if (allowed.matches(fqcn)) Nil
          else List(WhitelistViolation(tpe.pos, fqcn))
        }
      }
    }
}

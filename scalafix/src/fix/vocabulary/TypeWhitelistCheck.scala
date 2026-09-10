package fix.vocabulary

import scala.meta._
import scalafix.v1._

final case class WhitelistViolation(
    position: scala.meta.inputs.Position,
    foundFqcn: String
)

object TypeWhitelistCheck {

  /** `type Error = String` binds a concrete implementation to an abstract type
    * member -- a type-alias binding, not a value-bearing member type, so it
    * gets the same exemption as the abstract member it implements.
    */
  private def isTypeAliasBinding(node: Tree): Boolean =
    node.parent.exists {
      case dt: Defn.Type => dt.body eq node
      case _             => false
    }

  /** A class/trait/object/type's own name, at its definition site, is a
    * declaration -- not a reference to a named type. `final case class
    * Holder(...)` doesn't "name" `Holder`, it brings it into existence.
    */
  private def isOwnDefinitionName(node: Tree): Boolean =
    node.parent.exists {
      case c: Defn.Class  => c.name eq node
      case t: Defn.Trait  => t.name eq node
      case o: Defn.Object => o.name eq node
      case dt: Defn.Type  => dt.name eq node
      case _              => false
    }

  private def namedTypesIn(tree: Tree): List[Type] =
    tree
      .collect {
        case t: Type.Name   => t
        case t: Type.Select => t
      }
      .filterNot(t => isTypeAliasBinding(t) || isOwnDefinitionName(t))

  def violations(tree: Tree, allowed: PatternList)(implicit
      doc: SemanticDocument
  ): List[WhitelistViolation] = {
    val namedViolations = namedTypesIn(tree).flatMap { tpe =>
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
    val functionViolations =
      tree.collect { case t: Type.Function => t }.flatMap { tf =>
        val fqcn = s"scala.Function${tf.paramClause.values.size}"
        if (allowed.matches(fqcn)) Nil
        else List(WhitelistViolation(tf.pos, fqcn))
      }
    namedViolations ++ functionViolations
  }
}

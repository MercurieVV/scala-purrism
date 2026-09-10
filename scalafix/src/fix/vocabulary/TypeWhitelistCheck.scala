package fix.vocabulary

import scala.meta._
import scalafix.v1._

final case class WhitelistViolation(
    position: scala.meta.inputs.Position,
    foundFqcn: String
)

object TypeWhitelistCheck {

  private def namedTypesIn(tree: Tree): List[Type] =
    tree.collect {
      case t: Type.Name   => t
      case t: Type.Select => t
    }

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

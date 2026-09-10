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
    * type's own head (`Either`) is a "named type". Likewise `Int => Int` sugar
    * (`Type.Function`) has no literal `Function1` node to check, so its operand
    * types are exempt the same way -- the `Type.Function` node itself is
    * checked as a head instead (see `violations`, which computes its synthetic
    * `scala.FunctionN` name directly rather than resolving a symbol --
    * arrow-sugar nodes don't carry one). A node counts as an argument, and is
    * skipped, if any ancestor in its parent chain is a `Type.ArgClause` or a
    * `Type.Function` -- regardless of nesting depth, so an argument of an
    * argument is still skipped.
    */
  private def insideArgumentPosition(node: Tree): Boolean = {
    @scala.annotation.tailrec
    def loop(t: Tree): Boolean = t.parent match {
      case Some(_: Type.ArgClause) => true
      case Some(_: Type.Function)  => true
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
      .filterNot(t => insideArgumentPosition(t) || isTypeAliasBinding(t))

  private def functionSugarIn(tree: Tree): List[Type.Function] =
    tree
      .collect { case t: Type.Function => t }
      .filterNot(insideArgumentPosition)

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
    val functionViolations = functionSugarIn(tree).flatMap { tf =>
      val fqcn = s"scala.Function${tf.paramClause.values.size}"
      if (allowed.matches(fqcn)) Nil
      else List(WhitelistViolation(tf.pos, fqcn))
    }
    namedViolations ++ functionViolations
  }
}

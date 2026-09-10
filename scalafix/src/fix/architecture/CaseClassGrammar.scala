package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `case class` constructor params and `var` members.
  * Composition-expression bodies (for extra `val`/`def` members) are checked
  * separately in a later task; this task only rejects `var` outright and
  * non-arrow-slot constructor params.
  */
object CaseClassGrammar {

  def findings(
      defn: Defn.Class
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
    val stats = defn.templ.body.stats
    val slots =
      ArrowSlot.declaredOn(defn.tparamClause) ++ ArrowSlot
        .declaredAsAbstractMember(stats)
    val paramFindings = defn.ctor.paramClauses
      .flatMap(_.values)
      .flatMap(checkParam(_, slots))
      .toList
    val varFindings = stats.collect { case v: Defn.Var =>
      ArchitectureFinding(
        v,
        "`var` is not allowed; only arrow-slot-typed vals/defs are"
      )
    }
    val bodyFindings = stats.collect {
      case d: Defn.Def if !CompositionExpr.isValid(d.body, slots) =>
        ArchitectureFinding(
          d.body,
          s"body of `${d.name.value}` is not a valid composition expression " +
            "(only arrow-slot references, whitelisted combinators, and " +
            "local vals ending in one final composition are allowed)"
        )
      case d: Defn.Val if !CompositionExpr.isValid(d.rhs, slots) =>
        ArchitectureFinding(
          d.rhs,
          "val body is not a valid composition expression"
        )
    }
    paramFindings ++ varFindings ++ bodyFindings
  }

  private def checkParam(
      param: Term.Param,
      slots: List[ArrowSlot]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    param.decltpe.flatMap { tpe =>
      if (ArrowSlot.isSlotApplication(tpe, slots)) None
      else if (ArrowSlot.isArrowConvertApplication(tpe)) None
      else if (isAbstractTypeReference(tpe)) None
      else if (ArrowSlot.namesConcreteArrow(tpe))
        Some(
          ArchitectureFinding(
            param,
            s"constructor param `${param.name.value}` names a concrete type " +
              s"`${tpe.syntax}`; only an abstract arrow slot or an abstract " +
              "type is allowed here"
          )
        )
      else
        Some(
          ArchitectureFinding(
            param,
            s"constructor param `${param.name.value}` has plain data type " +
              s"`${tpe.syntax}`; only arrow-slot-typed or abstract-typed " +
              "params are allowed"
          )
        )
    }

  private def isAbstractTypeReference(
      tpe: Type
  )(implicit doc: SemanticDocument): Boolean =
    tpe match {
      case Type.Name(_) =>
        val sym = tpe.symbol
        sym != Symbol.None && doc.info(sym).exists(_.isAbstract)
      case _ => false
    }
}

package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for plain objects and companion-object constructors. A plain
  * object's `def`s may be generic in an arrow slot (pure forwarding) but may
  * not carry a typeclass-evidence bound on it; that's reserved for
  * companion-object constructors, whose body must still be a valid composition
  * expression and whose return type must be the enclosing module or an
  * arrow-slot type.
  */
object ObjectGrammar {

  def findings(
      defn: Defn.Object,
      isCompanion: Boolean
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] =
    defn.templ.body.stats.collect {
      case d: Defn.Def =>
        val ownTparamClauses = d.paramClauseGroups.map(_.tparamClause)
        val ownSlots = ownTparamClauses.flatMap(ArrowSlot.declaredOn)
        val hasEvidence = ownTparamClauses.exists(hasContextBound)
        if (hasEvidence && !isCompanion)
          Some(
            ArchitectureFinding(
              d,
              s"`${d.name.value}` carries a typeclass-evidence bound in a " +
                "plain (non-companion) object; evidence bounds are reserved " +
                "for companion-object constructors"
            )
          )
        else if (
          !CompositionExpr.isValid(
            d.body,
            ownSlots,
            if (isCompanion) Set(defn.name.value) else Set.empty
          )
        )
          Some(
            ArchitectureFinding(
              d.body,
              s"body of `${d.name.value}` is not a valid composition expression"
            )
          )
        else None
      case v: Defn.Val =>
        if (!CompositionExpr.isValid(v.rhs, Nil))
          Some(
            ArchitectureFinding(
              v.rhs,
              "val body is not a valid composition expression"
            )
          )
        else None
      case _: Import => None // imports are unrestricted, per the spec
      case other =>
        Some(
          ArchitectureFinding(
            other,
            "only val/def members are allowed in an object here"
          )
        )
    }.flatten

  private def hasContextBound(tparamClause: Type.ParamClause)(implicit
      doc: SemanticDocument
  ): Boolean =
    tparamClause.values.exists { tparam =>
      Type.Param.After_4_6_0.unapply(tparam) match {
        case Some((_, _, _, _, _, cbounds)) => cbounds.nonEmpty
        case None                           => false
      }
    }
}

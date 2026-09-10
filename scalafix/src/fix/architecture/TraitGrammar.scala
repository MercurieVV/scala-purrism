package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `trait`/abstract `class` bodies. Grows across later tasks
  * (slot form 2, monomorphic-member check, supertype conformance, the
  * ArrowConvert special case).
  */
object TraitGrammar {

  def findings(
      defn: Defn.Trait
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] =
    if (isArrowConvertShape(defn)) Nil else memberFindings(defn)

  private def memberFindings(
      defn: Defn.Trait
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
    val stats = defn.templ.body.stats
    val slots =
      ArrowSlot.declaredOn(defn.tparamClause) ++ ArrowSlot
        .declaredAsAbstractMember(stats)
    val slotNames = slots.map(_.name).toSet
    stats.collect {
      case decl: Decl.Def if hasOwnTypeParams(decl) =>
        Some(
          ArchitectureFinding(
            decl,
            s"member `${decl.name.value}` is generic beyond its arrow slot's " +
              "own two holes; arrow-slot members must be monomorphic"
          )
        )
      case decl: Decl.Def
          if ArrowSlot.requiresArrowInstanceForOneOf(slotNames, decl) =>
        None // the instance-requirement declaration for a form-2 slot
      case decl: Decl.Def => checkMember(decl.decltpe, decl, slots)
      case decl: Decl.Val
          if ArrowSlot.requiresArrowInstanceForOneOf(slotNames, decl) =>
        None
      case decl: Decl.Val => checkMember(decl.decltpe, decl, slots)
      case _: Decl.Type   => None // abstract type members are always allowed
    }.flatten
  }

  private def hasOwnTypeParams(decl: Decl.Def): Boolean =
    decl.paramClauseGroups.exists(_.tparamClause.values.nonEmpty)

  /** Whether `defn` is exactly the ArrowConvert shape: two type parameters `P`,
    * `Q` (each of kind `(*, *) => *`, no bound required) and a single abstract
    * method whose only type parameters (`A`, `B`) are consumed by applying
    * `P`/`Q` to them -- `def apply[A, B](p: P[A, B]): Q[A, B]`. Special-cased
    * per the spec's "Cross-slot conversion" section, not a general opening for
    * per-method type parameters.
    */
  private def isArrowConvertShape(defn: Defn.Trait): Boolean =
    defn.tparamClause.values match {
      case List(p, q) if isBinaryHole(p) && isBinaryHole(q) =>
        defn.templ.body.stats match {
          case List(decl: Decl.Def) if decl.name.value == "apply" =>
            decl.paramClauseGroups match {
              case List(group) =>
                (
                  group.tparamClause.values,
                  group.paramClauses.flatMap(_.values)
                ) match {
                  case (List(a, b), List(param)) =>
                    val names = List(a.name.value, b.name.value)
                    param.decltpe.exists(
                      matchesApplication(_, p.name.value, names)
                    ) &&
                    matchesApplication(decl.decltpe, q.name.value, names)
                  case _ => false
                }
              case _ => false
            }
          case _ => false
        }
      case _ => false
    }

  private def isBinaryHole(tparam: Type.Param): Boolean =
    tparam.tparamClause.values.size == 2

  private def matchesApplication(
      tpe: Type,
      headName: String,
      argNames: List[String]
  ): Boolean =
    tpe match {
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((Type.Name(h), args)) =>
            h == headName && args.values.collect { case Type.Name(n) =>
              n
            } == argNames
          case _ => false
        }
      case _ => false
    }

  private def checkMember(
      decltpe: Type,
      member: Tree,
      slots: List[ArrowSlot]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    if (ArrowSlot.isSlotApplication(decltpe, slots)) None
    else if (ArrowSlot.namesConcreteArrow(decltpe))
      Some(
        ArchitectureFinding(
          member,
          s"member names a concrete type `${decltpe.syntax}`; only an abstract " +
            "arrow slot (a type parameter or type member bounded by " +
            "Arrow/Compose/Category) applied to two types is allowed here"
        )
      )
    else
      Some(
        ArchitectureFinding(
          member,
          s"member has non-arrow type `${decltpe.syntax}`; only an abstract " +
            "arrow slot applied to two types, or an abstract type member, is " +
            "allowed here"
        )
      )
}

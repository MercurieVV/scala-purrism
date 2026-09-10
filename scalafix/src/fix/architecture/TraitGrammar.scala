package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `trait`/abstract `class` bodies. Grows across later tasks
  * (slot form 2, monomorphic-member check, supertype conformance, the
  * ArrowConvert special case).
  */
object TraitGrammar {

  def findings(
      defn: Defn.Trait,
      allowedConcreteTypePatterns: List[String]
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] =
    if (isArrowConvertShape(defn)) Nil
    else memberFindings(defn, allowedConcreteTypePatterns)

  private def memberFindings(
      defn: Defn.Trait,
      allowedConcreteTypePatterns: List[String]
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
      case decl: Decl.Def =>
        checkMember(decl.decltpe, decl, slots, allowedConcreteTypePatterns)
      case decl: Decl.Val
          if ArrowSlot.requiresArrowInstanceForOneOf(slotNames, decl) =>
        None
      case decl: Decl.Val =>
        checkMember(decl.decltpe, decl, slots, allowedConcreteTypePatterns)
      case _: Decl.Type => None // abstract type members are always allowed
    }.flatten ++ supertypeFindings(defn, allowedConcreteTypePatterns)
  }

  /** Whether each `extends`/`with` supertype is conforming: either a marker
    * trait contributing zero members (e.g. `Serializable`), or the same
    * arrow-slot principle applied elsewhere. Re-verifying the full grammar from
    * a bare symbol isn't possible (no source tree to re-walk), so a non-marker
    * supertype declines with a diagnostic rather than guessing, per the spec's
    * explicit fallback.
    */
  private def supertypeFindings(
      defn: Defn.Trait,
      allowedConcreteTypePatterns: List[String]
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] =
    defn.templ.inits.flatMap { init =>
      val sym = init.tpe.symbol
      if (sym == Symbol.None) Nil
      else
        sameFileTrait(sym) match {
          case Some(superTrait) =>
            if (findings(superTrait, allowedConcreteTypePatterns).isEmpty) Nil
            else List(nonConformingFinding(init, sym.displayName))
          case None if knownMarkerTraits(sym.value) => Nil
          case None                                 =>
            // A symbol from another file or library can't reliably be
            // re-verified through this single-document API (this codebase's
            // own convention for genuine cross-file analysis is a
            // project-wide payload scan, per KleisliLiftScope -- see
            // docs/RULES.md, and `doc.info` doesn't resolve external
            // symbols here either). So anything we can't positively prove
            // is a zero-member marker declines, rather than silently
            // passing an unresolved or non-conforming supertype.
            doc.info(sym) match {
              case Some(info) if declaresNoMembers(info) => Nil
              case _ => List(cantVerifyFinding(init, sym.displayName))
            }
        }
    }

  private def sameFileTrait(sym: Symbol)(implicit
      doc: SemanticDocument
  ): Option[Defn.Trait] =
    doc.tree.collect { case t: Defn.Trait if t.symbol == sym => t }.headOption

  private def nonConformingFinding(
      init: Init,
      name: String
  ): ArchitectureFinding =
    ArchitectureFinding(
      init,
      s"supertype `$name` does not itself conform to this grammar; a " +
        "trait may only extend a marker trait (contributing zero members) " +
        "or another trait built on the same arrow-slot principle"
    )

  private def cantVerifyFinding(init: Init, name: String): ArchitectureFinding =
    ArchitectureFinding(
      init,
      s"supertype `$name`'s shape can't be verified from here; extend " +
        "only marker traits (contributing zero members) or traits defined " +
        "in the same file, or narrow the rule to run per-module so " +
        "supertypes stay locally checkable"
    )

  /** Well-known zero-member marker traits, curated the same way `ArrowFamily`'s
    * typeclass set is: `doc.info` doesn't resolve external (library) symbols
    * reliably in this single-document API, so these are recognized by symbol
    * name rather than by re-deriving "zero members" from an unavailable
    * signature.
    */
  private val knownMarkerTraits: Set[String] =
    Set(
      "scala/package.Serializable#",
      "java/io/Serializable#",
      "scala/Product#",
      "scala/Equals#"
    )

  private def declaresNoMembers(info: SymbolInformation): Boolean =
    info.signature match {
      case cs: ClassSignature => cs.declarations.forall(_.isConstructor)
      case _                  => true
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
      slots: List[ArrowSlot],
      allowedConcreteTypePatterns: List[String]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    if (
      ArrowSlot.isSlotApplication(decltpe, slots, allowedConcreteTypePatterns)
    ) None
    else if (ArrowSlot.isSlotShape(decltpe, slots))
      Some(
        ArchitectureFinding(
          member,
          s"member applies its arrow slot to a concrete type not covered by " +
            s"`allowedConcreteTypePatterns` in `${decltpe.syntax}`; only " +
            "abstract type parameters, Either/Option/tuples, or a " +
            "configured pattern are allowed as slot arguments"
        )
      )
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

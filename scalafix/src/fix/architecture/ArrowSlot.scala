package fix.architecture

import scala.meta._
import scalafix.v1._

/** A binary type (kind `(*, *) => *`) standing in for a not-yet-chosen concrete
  * arrow. This task recognises only the type-parameter-with- context-bound
  * form, e.g. the `Step` in `trait Pipeline[Step[_, _]: Arrow]`. The
  * abstract-type-member form is added in Task 2.
  */
final case class ArrowSlot(name: String)

object ArrowSlot {

  def declaredOn(
      tparamClause: Type.ParamClause
  )(implicit doc: SemanticDocument): List[ArrowSlot] =
    tparamClause.values.flatMap { tparam =>
      Type.Param.After_4_6_0.unapply(tparam) match {
        case Some((_, name, holes, _, _, cbounds))
            if holes.values.size == 2 && cbounds.exists(isArrowBound) =>
          Some(ArrowSlot(name.value))
        case _ => None
      }
    }

  /** Slots declared as an abstract type member with a separate instance
    * requirement, e.g. `type Step[_, _]` plus an abstract `def`/`val` elsewhere
    * in the same template whose declared type is `Arrow[Step]` (or a
    * Compose/Category-family bound).
    */
  def declaredAsAbstractMember(
      stats: List[Stat]
  )(implicit doc: SemanticDocument): List[ArrowSlot] = {
    val typeMembers = stats.collect {
      case dt: Decl.Type if dt.tparamClause.values.size == 2 => dt.name.value
    }
    typeMembers
      .filter(name => stats.exists(requiresArrowInstanceForOneOf(Set(name), _)))
      .map(ArrowSlot(_))
  }

  /** Whether `stat` is an abstract `def`/`val` whose declared type is
    * `Arrow[Step]` (or a Compose/Category-family bound) for some `Step` in
    * `slotNames`. Such a member is the instance-requirement declaration itself,
    * not an ordinary arrow-slot member -- callers exclude it from the
    * member-type grammar check.
    */
  def requiresArrowInstanceForOneOf(slotNames: Set[String], stat: Stat)(implicit
      doc: SemanticDocument
  ): Boolean = {
    def mentionsSlotUnderArrow(tpe: Type): Boolean = tpe match {
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((head, args)) =>
            args.values match {
              case List(Type.Name(n)) if slotNames(n) =>
                val sym = head.symbol
                sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
              case _ => false
            }
          case _ => false
        }
      case _ => false
    }
    stat match {
      case decl: Decl.Def => mentionsSlotUnderArrow(decl.decltpe)
      case decl: Decl.Val => mentionsSlotUnderArrow(decl.decltpe)
      case _              => false
    }
  }

  private def isArrowBound(
      bound: Type
  )(implicit doc: SemanticDocument): Boolean = {
    val sym = bound.symbol
    sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
  }

  /** Whether `tpe` is exactly `Step[A, B]` for one of `slots`, with both `A`
    * and `B` themselves allowed per [[isAllowedArgument]] -- the only shape a
    * member's declared type is allowed to take.
    */
  def isSlotApplication(
      tpe: Type,
      slots: List[ArrowSlot],
      allowedConcreteTypePatterns: List[String]
  )(implicit doc: SemanticDocument): Boolean =
    slotArguments(tpe, slots).exists(
      _.forall(isAllowedArgument(_, allowedConcreteTypePatterns))
    )

  /** Whether `tpe` has the right shape and head to be a slot application --
    * `Step[_, _]` for one of `slots` -- without checking the arguments
    * themselves. Used to give a precise diagnostic: "this is a slot application
    * with a disallowed argument" reads differently from "this isn't a slot
    * application at all".
    */
  def isSlotShape(tpe: Type, slots: List[ArrowSlot]): Boolean =
    slotArguments(tpe, slots).isDefined

  private def slotArguments(
      tpe: Type,
      slots: List[ArrowSlot]
  ): Option[List[Type]] =
    tpe match {
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((Type.Name(n), args))
              if args.values.size == 2 && slots.exists(_.name == n) =>
            Some(args.values)
          case _ => None
        }
      case _ => None
    }

  /** Whether `sym` is a type parameter, an abstract type member, or a type
    * alias -- anything whose semanticdb signature is a `TypeSignature` rather
    * than a `ClassSignature`. `isAbstract`/`isTypeParameter` don't reliably
    * flag a bare `type X` declaration (Scala has no `abstract` keyword for
    * types), so the signature shape is the robust signal: a real
    * class/trait/object like `Int` or `Either` always has a `ClassSignature`.
    */
  private def isAbstractTypeSlot(sym: Symbol)(implicit
      doc: SemanticDocument
  ): Boolean =
    doc.info(sym).exists(_.signature.isInstanceOf[TypeSignature])

  /** The types the standard library provides purely as a product/sum-type
    * wrapper, with no leaf types of their own -- allowed by default as a slot
    * argument, unlike other concrete types, which need an explicit
    * `allowedConcreteTypePatterns` entry.
    */
  private val structuralWrappers: Set[String] = Set(
    "scala/util/Either#",
    "scala/package.Either#",
    "scala/Option#"
  )

  /** Whether `tpe` is allowed as one of the two types a slot is applied to (the
    * `A`/`B` in `Step[A, B]`). Recursive: every concrete type name found
    * anywhere inside `tpe` must be allowed, not just the outermost one. Allowed
    * are: an abstract type parameter or abstract type member (in scope wherever
    * `tpe` is written); a Scala 3 literal tuple of any arity, each element
    * itself checked; `Either`/`Option`, whose own type arguments are still
    * checked; or a type whose fully-qualified symbol matches one of
    * `allowedConcreteTypePatterns` (each entry a regex).
    */
  def isAllowedArgument(
      tpe: Type,
      allowedConcreteTypePatterns: List[String]
  )(implicit doc: SemanticDocument): Boolean =
    tpe match {
      case tuple: Type.Tuple =>
        tuple.args.forall(isAllowedArgument(_, allowedConcreteTypePatterns))
      case name: Type.Name =>
        val sym = name.symbol
        sym != Symbol.None &&
        (isAbstractTypeSlot(sym) ||
          structuralWrappers(sym.value) ||
          allowedConcreteTypePatterns.exists(sym.value.matches))
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((head, args)) =>
            val sym = head.symbol
            val headAllowed = sym != Symbol.None &&
              (structuralWrappers(sym.value) ||
                allowedConcreteTypePatterns.exists(sym.value.matches))
            headAllowed && args.values.forall(
              isAllowedArgument(_, allowedConcreteTypePatterns)
            )
          case None => false
        }
      case _ => false
    }

  /** Whether `tpe` is `ArrowConvert[P, Q]` for two type names -- the
    * sanctioned, fully-abstract cross-slot conversion evidence (see the spec's
    * "Cross-slot conversion" section). Recognised by name: it's this rule's own
    * shared vocabulary type, not a general two-hole shape.
    */
  def isArrowConvertApplication(tpe: Type): Boolean = tpe match {
    case apply: Type.Apply =>
      Type.Apply.After_4_6_0.unapply(apply) match {
        case Some((Type.Name("ArrowConvert"), args)) =>
          args.values match {
            case List(Type.Name(_), Type.Name(_)) => true
            case _                                => false
          }
        case _ => false
      }
    case _ => false
  }

  /** Whether `tpe` names a concrete type with its own resolvable Arrow- family
    * instance (`Kleisli`, a plain `A => B`, or a custom Arrow instance) rather
    * than applying an abstract slot -- the specific violation the spec calls
    * out as "naming a concrete arrow type".
    */
  def namesConcreteArrow(tpe: Type)(implicit doc: SemanticDocument): Boolean =
    tpe match {
      case _: Type.Function => true
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((head, _)) =>
            val sym = head.symbol
            sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
          case None => false
        }
      case _ => false
    }
}

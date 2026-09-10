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

  /** Whether `tpe` is exactly `Step[A, B]` for one of `slots` -- the only shape
    * a member's declared type is allowed to take.
    */
  def isSlotApplication(tpe: Type, slots: List[ArrowSlot]): Boolean =
    tpe match {
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((Type.Name(n), args)) if args.values.size == 2 =>
            slots.exists(_.name == n)
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

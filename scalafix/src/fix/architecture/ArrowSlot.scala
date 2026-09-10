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

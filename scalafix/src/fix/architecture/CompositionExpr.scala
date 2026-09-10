package fix.architecture

import scala.meta._
import scalafix.v1._

/** The composition-expression grammar: the only shape a non-abstract
  * arrow-slot-typed `val`/`def` body may take, per the spec's "Composition
  * expressions" section.
  *
  * `moduleNames` is non-empty only when checking a companion-object
  * constructor's body: it names the enclosing module (trait/case class) that
  * body is allowed to construct, per the spec's "Companion-object constructors"
  * section.
  */
object CompositionExpr {

  private val combinators =
    Set(
      "andThen",
      "compose",
      ">>>",
      "<<<",
      "first",
      "second",
      "split",
      "&&&",
      "|||",
      "id"
    )

  def isValid(
      term: Term,
      slots: List[ArrowSlot],
      moduleNames: Set[String] = Set.empty
  )(implicit doc: SemanticDocument): Boolean =
    term match {
      case Term.Name(_)                 => true // a bare reference
      case Term.Select(_, Term.Name(_)) => true // eta-expanded method reference
      case infix: Term.ApplyInfix =>
        Term.ApplyInfix.After_4_6_0.unapply(infix) match {
          case Some((lhs, Term.Name(op), _, args))
              if combinators(op) && args.values.size == 1 =>
            isValid(lhs, slots, moduleNames) && isValid(
              args.values.head,
              slots,
              moduleNames
            )
          case _ => false
        }
      case apply: Term.Apply =>
        Term.Apply.After_4_6_0.unapply(apply) match {
          case Some((Term.Select(recv, Term.Name(op)), args))
              if combinators(op) && args.values.size == 1 =>
            isValid(recv, slots, moduleNames) && isValid(
              args.values.head,
              slots,
              moduleNames
            )
          case Some((Term.Name(n), args)) if moduleNames(n) =>
            args.values.forall(argIsValid(_, slots, moduleNames))
          case _ => false
        }
      case Term.Block(stats) =>
        stats.nonEmpty && stats.init.forall {
          case Defn.Val(_, _, _, rhs) => isValid(rhs, slots, moduleNames)
          case _                      => false
        } && (stats.last match {
          case t: Term => isValid(t, slots, moduleNames)
          case _       => false
        })
      case _ => false
    }

  private def argIsValid(
      arg: Term,
      slots: List[ArrowSlot],
      moduleNames: Set[String]
  )(implicit doc: SemanticDocument): Boolean = arg match {
    case Term.Assign(_, value) => isValid(value, slots, moduleNames)
    case other                 => isValid(other, slots, moduleNames)
  }
}

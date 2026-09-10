package fix.architecture

import scala.meta._
import scalafix.v1._

/** The composition-expression grammar: the only shape a non-abstract
  * arrow-slot-typed `val`/`def` body may take, per the spec's "Composition
  * expressions" section.
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

  def isValid(term: Term, slots: List[ArrowSlot])(implicit
      doc: SemanticDocument
  ): Boolean =
    term match {
      case Term.Name(_)                 => true // a bare reference
      case Term.Select(_, Term.Name(_)) => true // eta-expanded method reference
      case infix: Term.ApplyInfix =>
        Term.ApplyInfix.After_4_6_0.unapply(infix) match {
          case Some((lhs, Term.Name(op), _, args))
              if combinators(op) && args.values.size == 1 =>
            isValid(lhs, slots) && isValid(args.values.head, slots)
          case _ => false
        }
      case apply: Term.Apply =>
        Term.Apply.After_4_6_0.unapply(apply) match {
          case Some((Term.Select(recv, Term.Name(op)), args))
              if combinators(op) && args.values.size == 1 =>
            isValid(recv, slots) && isValid(args.values.head, slots)
          case _ => false
        }
      case Term.Block(stats) =>
        stats.nonEmpty && stats.init.forall {
          case Defn.Val(_, _, _, rhs) => isValid(rhs, slots)
          case _                      => false
        } && (stats.last match {
          case t: Term => isValid(t, slots)
          case _       => false
        })
      case _ => false
    }
}

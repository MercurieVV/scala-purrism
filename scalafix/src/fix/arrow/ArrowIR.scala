package fix.arrow

import scala.meta._

/** A small arrow algebra: the intermediate form between a monadic Kleisli body
  * and its point-free rendering.
  *
  * The shipped `PreferArrow` had three hand-written syntactic matchers, one per
  * output shape, each capped at the arity it was written for. Routing every
  * shape through this algebra replaces that with one fold: the parser produces
  * an `ArrowIR`, the normalizer canonicalises it, the budget inspects it, and
  * the renderer prints it. New shapes are new constructors, not new matchers.
  *
  * [[Opaque]] is what makes partial analysis safe. A subtree the parser cannot
  * understand becomes an `Opaque` leaf rather than a parse failure, and the
  * readability budget then rejects any tree still containing one -- so the rule
  * emits a whole rewrite or none, never a half-rewrite with a hole in it.
  */
sealed trait ArrowIR

object ArrowIR {

  /** The identity arrow -- `Kleisli.ask` in emitted code, or elided by
    * normalization when it sits in a composition.
    */
  case object Id extends ArrowIR

  /** A pure `A => B`, rendered as the function term `fn`. */
  final case class Lift(fn: Term) extends ArrowIR

  /** A Kleisli-typed leaf: the receiver expression, e.g. `loadUser`. */
  final case class Eff(callee: Term) extends ArrowIR

  /** A plain effectful expression lifted into a Kleisli *in place* --
    * `Kleisli { (p: T) => body }` -- where `body` is an `F`-returning
    * expression that is a function of the arrow input `p`. Distinct from
    * [[Eff]] (which names an existing Kleisli value) and [[Lift]] (a pure
    * `A => B` via `Kleisli.pure`): here the effect is inline and would not
    * otherwise be a Kleisli at all. Produced only in aggressive mode, where a
    * `for` generator that calls a plain effectful method is lifted so the
    * independent generators can fan out with `&&&`.
    */
  final case class LiftK(param: String, tpe: String, body: Term) extends ArrowIR

  /** A typed `Kleisli.ask[F, A]` -- the arrow that returns its own input. Used
    * in aggressive mode to retain the input alongside fanned-out results when
    * the `yield` still references it. Carries both types because bare
    * `Kleisli.ask` does not infer them inside an `&&&`.
    */
  final case class Ask(effect: String, inputTpe: String) extends ArrowIR

  /** `l >>> r`. */
  final case class AndThen(l: ArrowIR, r: ArrowIR) extends ArrowIR

  /** `l &&& r` -- both arrows fed the same input, results tupled. */
  final case class Merge(l: ArrowIR, r: ArrowIR) extends ArrowIR

  /** `l ||| r` -- an `Either`-typed input routed to one arrow or the other. */
  final case class Choice(l: ArrowIR, r: ArrowIR) extends ArrowIR

  /** `l *> r` -- both arrows fed the same input, left's result discarded.
    *
    * The shape a `for` writes as a discard generator, `_ <- log(...)`, ahead of
    * the work whose result it keeps. `*>` needs only `Apply[F]`, weaker than
    * the `Monad[F]` an `&&&` fan-out costs, and it feeds the same input to both
    * sides -- so the whole `ask &&& … .map(_._2)` plumbing that would otherwise
    * be needed to carry the kept value past the discarded one disappears.
    */
  final case class ProductR(l: ArrowIR, r: ArrowIR) extends ArrowIR

  /** `a.flatTap { <binders> => tap }` -- run `tap` on `a`'s result and keep
    * `a`'s result.
    *
    * The mirror of [[ProductR]]: a discard generator *after* the work, whose
    * right-hand side reads what the work produced. `binders` names the arms `a`
    * produces, in arm order, so the tap can refer to them exactly as the source
    * `for` did; more than one arm is destructured out of the left-nested tuple
    * an `&&&` chain yields.
    */
  final case class FlatTap(a: ArrowIR, binders: List[String], tap: ArrowIR)
      extends ArrowIR

  /** `a.local(fn)` -- reshape the input with the pure `fn` before `a`. */
  final case class Local(fn: Term, a: ArrowIR) extends ArrowIR

  /** `a.map(fn)` -- reshape the output of `a` with the pure `fn`. */
  final case class Rmap(a: ArrowIR, fn: Term) extends ArrowIR

  /** A subtree the parser could not analyse. Never survives the budget. */
  final case class Opaque(term: Term) extends ArrowIR

  /** Every leaf term reachable in `ir`, for the "contains Opaque" and "effect
    * count" checks the budget needs.
    */
  def fold[A](ir: ArrowIR)(z: A)(op: (A, ArrowIR) => A): A = {
    val here = op(z, ir)
    ir match {
      case AndThen(l, r)    => fold(r)(fold(l)(here)(op))(op)
      case Merge(l, r)      => fold(r)(fold(l)(here)(op))(op)
      case Choice(l, r)     => fold(r)(fold(l)(here)(op))(op)
      case ProductR(l, r)   => fold(r)(fold(l)(here)(op))(op)
      case FlatTap(a, _, t) => fold(t)(fold(a)(here)(op))(op)
      case Local(_, a)      => fold(a)(here)(op)
      case Rmap(a, _)       => fold(a)(here)(op)
      case _                => here
    }
  }

  def containsOpaque(ir: ArrowIR): Boolean =
    fold(ir)(false) {
      case (acc, _: Opaque) => true
      case (acc, _)         => acc
    }

  /** How many genuine effect leaves the tree carries. A single `Eff` is not a
    * composition -- there is nothing to gain by rewriting it -- so the budget
    * requires at least two.
    */
  def effectCount(ir: ArrowIR): Int =
    fold(ir)(0) {
      case (acc, _: Eff)   => acc + 1
      case (acc, _: LiftK) => acc + 1
      case (acc, _)        => acc
    }
}

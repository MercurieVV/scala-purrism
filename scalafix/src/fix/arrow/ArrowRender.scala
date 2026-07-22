package fix.arrow

import scala.meta.XtensionSyntax

import fix.arrow.ArrowIR._

/** Prints a normalized `ArrowIR` back to point-free source.
  *
  * `>>>`/`&&&`/`|||` are the operators the reference corpus already uses (43
  * `>>>`, 17 `|||`, and its two `&&&`), so the output is idiomatic there rather
  * than novel. Precedence: `>>>` and `&&&`/`|||` are all infix operators cats
  * defines; to keep the intended tree unambiguous every compound operand is
  * parenthesised, and only bare leaves are printed without.
  */
object ArrowRender {

  /** Fails loudly if an `Opaque` reaches rendering. The budget is supposed to
    * have rejected any such tree already; this is the backstop that keeps a
    * half-analysed body from being emitted as if it were understood.
    */
  def render(ir: ArrowIR): String =
    ir match {
      case Id               => "Kleisli.ask"
      case Ask(effect, tpe) => s"Kleisli.ask[$effect, $tpe]"
      case Lift(fn)         => s"Kleisli.pure(${fn.syntax})"
      case Eff(callee)      => callee.syntax
      case LiftK(param, tpe, body) =>
        s"Kleisli { ($param: $tpe) => ${body.syntax} }"
      // `>>>` is associative, so a chain needs no internal grouping; flatten it
      // to `a >>> b >>> c` rather than nesting parentheses.
      // `>>>`, `&&&` and `|||` are each associative, so a chain of one operator
      // needs no internal grouping. `&&&` and `|||` are left-associative, which
      // matches the nested-tuple / nested-`Either` shape their instances
      // produce, so flattening left-to-right is faithful.
      case chain: AndThen =>
        flattenAndThen(chain).map(infixOperand).mkString(" >>> ")
      case chain: Merge =>
        flattenMerge(chain).map(infixOperand).mkString(" &&& ")
      case chain: Choice =>
        flattenChoice(chain).map(infixOperand).mkString(" ||| ")
      case Local(fn, a) => s"${receiverOperand(a)}.local(${fn.syntax})"
      case Rmap(a, fn)  => s"${receiverOperand(a)}.map(${fn.syntax})"
      case Opaque(term) =>
        sys.error(
          s"ArrowRender reached an Opaque leaf (${term.syntax}); the budget " +
            "should have rejected this tree"
        )
    }

  private def flattenAndThen(ir: ArrowIR): List[ArrowIR] =
    ir match {
      case AndThen(l, r) => flattenAndThen(l) ++ flattenAndThen(r)
      case other         => List(other)
    }

  private def flattenMerge(ir: ArrowIR): List[ArrowIR] =
    ir match {
      case Merge(l, r) => flattenMerge(l) ++ flattenMerge(r)
      case other       => List(other)
    }

  private def flattenChoice(ir: ArrowIR): List[ArrowIR] =
    ir match {
      case Choice(l, r) => flattenChoice(l) ++ flattenChoice(r)
      case other        => List(other)
    }

  /** An operand of an infix arrow operator (`>>>`, `&&&`, `|||`). A `>>>` chain
    * needs no parens here: `>>>` binds tighter than `&&&`/`|||`, and is
    * associative among itself. A `.map`/`.local` receiver binds tightest of
    * all, so it also needs none. A different infix operator is wrapped, since
    * mixing `>>>` with `&&&`/`|||` in one expression should not lean on a
    * reader recalling their relative precedence.
    */
  private def infixOperand(ir: ArrowIR): String =
    ir match {
      case _: Eff | _: LiftK | Id | _: Ask | _: Lift | _: AndThen | _: Rmap |
          _: Local =>
        render(ir)
      case _ => s"(${render(ir)})"
    }

  /** The receiver of a `.map`/`.local`. A method call binds tighter than every
    * arrow operator, so any compound receiver -- including a `>>>` chain --
    * must be parenthesised, or `(a >>> b).map(f)` would degrade to
    * `a >>> b.map(f)`.
    */
  private def receiverOperand(ir: ArrowIR): String =
    ir match {
      case _: Eff | _: LiftK | Id | _: Ask | _: Lift => render(ir)
      case _                                         => s"(${render(ir)})"
    }
}

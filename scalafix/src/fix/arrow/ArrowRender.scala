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
      // `*>` binds tighter than every arrow operator (Scala reads precedence
      // off the first character, and `*` outranks `>`, `&` and `|`), so its own
      // operands may not lean on that: anything compound is parenthesised.
      case chain: ProductR =>
        flattenProductR(chain).map(productOperand).mkString(" *> ")
      case FlatTap(a, binders, tap) =>
        s"${receiverOperand(a)}.flatTap { ${binderPattern(binders)} => ${render(tap)} }"
      case Local(fn, a) => s"${receiverOperand(a)}.local(${fn.syntax})"
      case Rmap(a, fn)  => s"${receiverOperand(a)}.map(${fn.syntax})"
      case As(a, value) => s"${receiverOperand(a)}.as(${value.syntax})"
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

  private def flattenProductR(ir: ArrowIR): List[ArrowIR] =
    ir match {
      case ProductR(l, r) => flattenProductR(l) ++ flattenProductR(r)
      case other          => List(other)
    }

  /** The binder of a `.flatTap`. One arm binds a name; several arms arrive as
    * the left-nested tuple an `&&&` chain produces, so they are destructured
    * with a `case` pattern that re-establishes exactly the names the source
    * `for` bound. No arm to name at all still needs a parameter, hence `_`.
    */
  private def binderPattern(binders: List[String]): String =
    binders match {
      case Nil       => "_"
      case List(one) => one
      case many      => s"case ${nestPattern(many)}"
    }

  private def nestPattern(binders: List[String]): String =
    binders.reduceLeft((acc, name) => s"($acc, $name)")

  /** An operand of `*>`. Unlike `>>>` inside an `&&&`, nothing may be left bare
    * here on precedence grounds: `*>` binds tightest of the operators this
    * renderer emits, so an unparenthesised `a >>> b` operand would bind `b` to
    * the `*>` instead of the chain.
    */
  private def productOperand(ir: ArrowIR): String =
    ir match {
      case _: ProductR => render(ir)
      case _           => receiverOperand(ir)
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
          _: Local | _: FlatTap | _: As =>
        render(ir)
      case _ => s"(${render(ir)})"
    }

  /** The receiver of a `.map`/`.local`. A method call binds tighter than every
    * arrow operator, so any compound receiver -- including a `>>>` chain --
    * must be parenthesised, or `(a >>> b).map(f)` would degrade to
    * `a >>> b.map(f)`.
    *
    * A node that *renders as* a method call needs no parentheses of its own:
    * its own rendering already parenthesised whatever it wraps, so the result
    * ends in `.local(...)`/`.map(...)` and chains directly.
    */
  private def receiverOperand(ir: ArrowIR): String =
    ir match {
      case _: Eff | _: LiftK | Id | _: Ask | _: Lift => render(ir)
      case _: FlatTap | _: As | _: Local | _: Rmap   => render(ir)
      case _                                         => s"(${render(ir)})"
    }
}

package fix.idioms

import scala.meta._

/** `null`-guard idioms that are an `Option`.
  *
  * The shape these target is the Java-interop lookup:
  *
  * {{{
  * val session = sessions.get(handle)
  * if (session eq null) default else use(session)
  * }}}
  *
  * repeated once per accessor.
  * `Option(sessions.get(handle)).fold(default)(use)` is the same expression
  * with the branch named.
  */
private[fix] object OptionIdiomRules {

  import TermShapes._

  val ThrowingLookup: String =
    "`getOrElse(key, throw ...)` is a partial lookup wearing a total " +
      "signature. Return `Option` and let the caller decide."

  def rewrites(tree: Tree, mouse: Boolean): List[IdiomRewrite] =
    tree.collect { case term: Term =>
      nullGuard(term).orElse(if (mouse) cata(term) else None)
    }.flatten

  def findings(tree: Tree): List[IdiomFinding] =
    tree.collect {
      case term @ Term.Apply.After_4_6_0(
            Term.Select(_, Term.Name("getOrElse")),
            argClause
          ) if argClause.values.exists(throwsDirectly) =>
        IdiomFinding(term, ThrowingLookup)
    }

  /** A block of exactly `val v = <lookup>` followed by a `null` test on `v`. */
  private def nullGuard(term: Term): Option[IdiomRewrite] =
    term match {
      case Term.Block(
            List(
              Defn.Val(Nil, List(Pat.Var(Term.Name(bound))), None, lookup),
              Term.If.After_4_4_0(
                NullTest(tested, isNull),
                thenBranch,
                elseBranch,
                _
              )
            )
          ) if isName(tested, bound) =>
        val (empty, present) =
          if (isNull) (thenBranch, elseBranch) else (elseBranch, thenBranch)
        // `Option(x).fold(d)(f)` evaluates `d` only when the value is absent, so
        // a default that reads the bound name would be reading the `null` this
        // rewrite exists to remove.
        Option
          .when(
            !references(empty, bound) && references(present, bound) &&
              isSingleExpression(present)
          )(
            IdiomRewrite(
              term,
              braced(
                term,
                s"Option(${lookup.syntax}).fold(${empty.syntax})" +
                  s"($bound => ${present.syntax})"
              )
            )
          )
      case _ =>
        None
    }

  /** `opt.fold(d)(f)` -> `opt.cata(f, d)`, off by default.
    *
    * mouse's `cata` puts the two branches in the order they are read. Whether
    * that is worth a dependency is a project's call, which is why this is a
    * flag rather than a rule.
    */
  private def cata(term: Term): Option[IdiomRewrite] =
    term match {
      case CurriedCall(receiver, "fold", empty, function) =>
        Some(
          IdiomRewrite(
            term,
            s"${receiver.syntax}.cata(${function.syntax}, ${empty.syntax})"
          )
        )
      case _ =>
        None
    }

  /** Whether the present branch is one expression.
    *
    * A multi-statement branch survives the move into a lambda only as a
    * re-rendered block, and re-rendering drops the comments written between its
    * statements. Losing a comment that explains *why* realtime code is shaped
    * the way it is costs more than the rewrite gains, so those decline.
    */
  private def isSingleExpression(branch: Term): Boolean =
    branch match {
      case Term.Block(statements) => statements.sizeIs <= 1
      case _                      => !branch.pos.text.contains('\n')
    }

  /** Keeps the braces when the block was written with them.
    *
    * A brace-delimited block can be an *argument*: `guard(()) { … }` is
    * `guard(())({ … })` with the braces carrying the second argument list.
    * Replacing the block with a bare expression there yields
    * `guard(()) Option(x).fold(…)`, which does not parse. Where the source said
    * `{`, the replacement says `{` too.
    */
  private def braced(block: Term, replacement: String): String =
    if (block.pos.text.startsWith("{")) s"{ $replacement }" else replacement

  private def isName(term: Term, name: String): Boolean =
    term match {
      case Term.Name(`name`) => true
      case _                 => false
    }

  private def throwsDirectly(term: Term): Boolean =
    term.is[Term.Throw]
}

package fix.idioms

import scala.meta._

import fix.catsexpr.CatsFacts

/** Effect and error idioms: `try`/`catch`/`finally` and the mutable primitives
  * that predate `Ref`.
  *
  * Two of the four shapes rewrite and two report. The split is not timidity: a
  * `try`/`finally` becomes a `Resource` only if the body is already in `F`, and
  * an `AtomicReference` becomes a `Ref` only if every use of it is, and neither
  * fact is available from the shape alone. Editing them anyway produces code
  * that does not compile, which `docs/RULES.md` prefers a diagnostic to.
  */
private[fix] object EffectIdiomRules {

  import TermShapes._

  val ManualResource: String =
    "this `finally` closes a resource and does something else too, so the " +
      "close cannot be lifted out mechanically. `Using.resource` handles it " +
      "once the finally only closes; `Resource` once the body is in `F`."

  val UnsafeCast: String =
    "`asInstanceOf` is a claim the compiler cannot check. Model the case " +
      "in the type, or match on it."

  val MutableReference: String =
    "`AtomicReference` threading state through an effectful body is a " +
      "`cats.effect.Ref`. Rewriting it means lifting every use into `F`."

  def rewrites(
      tree: Tree,
      facts: CatsFacts
  ): List[IdiomRewrite] =
    tree.collect {
      case term: Term    => traverseUnit(term, facts).toList
      case attempt: Case => nonFatalNet(attempt).toList
    }.flatten ++ tree.collect { case attempt: Term.Try =>
      usingResource(attempt).toList
    }.flatten

  def findings(
      tree: Tree,
      refs: Boolean
  ): List[IdiomFinding] =
    tree.collect {
      case term: Term.Try
          if closesInFinally(term) && usingResource(term).isEmpty =>
        IdiomFinding(term, ManualResource)
      case term: Term.New if refs && isAtomicReference(term) =>
        IdiomFinding(term, MutableReference)
      case term: Term.ApplyType if isUnsafeCast(term) =>
        IdiomFinding(term, UnsafeCast)
    }

  /** `catch { case _: Throwable => … }` -> `catch { case NonFatal(_) => … }`.
    *
    * A catch-all net takes `VirtualMachineError` and `InterruptedException`
    * with it: the first cannot be recovered from and the second must be
    * re-thrown for cancellation to work at all. `NonFatal` is the net that was
    * meant.
    *
    * The pattern is narrowed rather than the whole `try` rewritten to
    * `Either.catchNonFatal(...).void`, which reads well but has type
    * `Either[Throwable, Unit]` where the `try` had `Unit` -- a rewrite that
    * changes the expression's type is a rewrite that stops compiling wherever
    * the value is used.
    */
  private def nonFatalNet(branch: Case): Option[IdiomRewrite] =
    Option
      .when(catchesEverything(branch.pat) && isCatchBranch(branch))(
        IdiomRewrite(
          branch.pat,
          bindingName(branch.pat).fold("NonFatal(_)")(name =>
            s"NonFatal($name)"
          ),
          needsNonFatal = true
        )
      )

  /** Whether this `case` is a `catch` handler rather than a `match` arm.
    *
    * The distinction matters: a catch-all in a `match` is exhaustiveness, not a
    * swallowed error.
    */
  private def isCatchBranch(branch: Case): Boolean =
    branch.parent.exists {
      case _: Term.Try => true
      case _: Term.CasesBlock =>
        branch.parent.flatMap(_.parent).exists(_.is[Term.Try])
      case _ => false
    }

  /** `opt.fold(F.unit)(f)` -> `opt.traverse_(f)`.
    *
    * `traverse_` is the same expression: run the effect for the value that is
    * there, do nothing for the value that is not. It says so in one word.
    */
  private def traverseUnit(term: Term, facts: CatsFacts): Option[IdiomRewrite] =
    term match {
      case CurriedCall(receiver, "fold", empty, function)
          if isUnitEffect(empty, facts) =>
        Some(
          IdiomRewrite(
            term,
            renderTraverse(receiver, function),
            needsCatsSyntax = true
          )
        )
      case _ =>
        None
    }

  /** `opt.traverse_(f)`, using the function's own braces where it has them.
    *
    * A block argument already carries them, so the parenthesised form wraps
    * them again: `traverse_({ x => … })` parses but reads as a slip.
    */
  private def renderTraverse(receiver: Term, function: Term): String = {
    val text = function.pos.text
    if (text.startsWith("{") && text.endsWith("}"))
      s"${receiver.pos.text}.traverse_ $text"
    else s"${receiver.pos.text}.traverse_($text)"
  }

  /** `Typeclass[F].unit`, or any Cats member spelled `unit`. */
  private def isUnitEffect(term: Term, facts: CatsFacts): Boolean =
    term match {
      case select @ Term.Select(receiver, Term.Name("unit")) =>
        facts.isCatsOperation(select) || isTypeclassApply(receiver, facts)
      case _ =>
        false
    }

  private def isTypeclassApply(term: Term, facts: CatsFacts): Boolean =
    term match {
      case applyType: Term.ApplyType =>
        facts
          .typeclassObject(applyType.fun)
          .exists(CatsFacts.Typeclasses.pure)
      case _ =>
        false
    }

  /** A pattern that catches every `Throwable`, including the fatal ones. */
  private def catchesEverything(pattern: Pat): Boolean =
    pattern match {
      case Pat.Wildcard()                       => true
      case Pat.Var(_)                           => true
      case Pat.Typed(_, Type.Name("Throwable")) => true
      case _                                    => false
    }

  /** The name a catch-all binds, when it binds one. */
  private def bindingName(pattern: Pat): Option[String] =
    pattern match {
      case Pat.Var(Term.Name(name))            => Some(name)
      case Pat.Typed(Pat.Var(Term.Name(n)), _) => Some(n)
      case _                                   => None
    }

  /** Whether the `finally` closes something, wherever in it that happens.
    *
    * `finally { log("done"); stream.close() }` still manages a resource by
    * hand; it is only the *rewrite* that needs the finally to do nothing else.
    */
  private def closesInFinally(term: Term.Try): Boolean =
    term.finallyp.exists { cleanup =>
      cleanup.collect {
        case Term.Select(_, Term.Name(name)) if CloseMethods.contains(name) =>
          ()
      }.nonEmpty
    }

  private val CloseMethods: Set[String] = Set("close", "release", "shutdown")

  /** `try body finally r.close()` -> `Using.resource(r)(_ => body)`.
    *
    * Type-preserving, which `Resource.fromAutoCloseable(...).use(...)` is not:
    * that yields an `F[A]` where the `try` yielded an `A`, so it only applies
    * once the body is already in `F`. These bodies are not -- they sit inside a
    * `Try`, a `Sync[F].blocking`, or a bare `Runnable` -- and rewriting them to
    * `Resource` would change the type of the expression around them.
    *
    * Only a stable identifier is accepted as the resource: `Using.resource`
    * evaluates its argument once, and re-evaluating an arbitrary expression
    * would acquire a second one.
    */
  private def usingResource(attempt: Term.Try): Option[IdiomRewrite] =
    for {
      closed <- closedIdentifier(attempt)
      if attempt.cases.isEmpty
      body = attempt.expr
    } yield IdiomRewrite(
      attempt,
      renderUsing(closed, body, attempt),
      needsUsing = true
    )

  /** `Using.resource(r)(_ => body)`, reusing the body's own braces.
    *
    * A block body already carries them, so wrapping it again nests two layers
    * for nothing. A single expression gets the parenthesised form.
    */
  private def renderUsing(
      closed: String,
      body: Term,
      attempt: Term.Try
  ): String = {
    val text = body.pos.text
    if (text.startsWith("{") && text.endsWith("}"))
      s"Using.resource($closed) { _ =>${text.drop(1).dropRight(1)}}"
    else if (text.contains('\n')) {
      // A position starts at the first token, not at the start of its line, so
      // splicing an indentation-syntax body drops the first line's indentation
      // and leaves the closing brace in column zero. Both columns are known;
      // put them back.
      val bodyIndent = " " * body.pos.startColumn
      val closeIndent = " " * attempt.pos.startColumn
      s"Using.resource($closed) { _ =>\n$bodyIndent$text\n$closeIndent}"
    } else s"Using.resource($closed)(_ => $text)"
  }

  /** The stable name the `finally` closes, when that is all it does. */
  private def closedIdentifier(attempt: Term.Try): Option[String] =
    attempt.finallyp.collect {
      case Term.Apply.After_4_6_0(
            Term.Select(Term.Name(name), Term.Name(method)),
            argClause
          ) if CloseMethods.contains(method) && argClause.values.isEmpty =>
        name
      case Term.Select(Term.Name(name), Term.Name(method))
          if CloseMethods.contains(method) =>
        name
    }

  private def isUnsafeCast(term: Term.ApplyType): Boolean =
    term.fun match {
      case Term.Select(_, Term.Name("asInstanceOf")) => true
      case _                                         => false
    }

  private def isAtomicReference(term: Term.New): Boolean =
    term.init.tpe match {
      case Type.Apply.After_4_6_0(Type.Name(name), _) => AtomicTypes(name)
      case Type.Name(name)                            => AtomicTypes(name)
      case Type.Select(_, Type.Name(name))            => AtomicTypes(name)
      case _                                          => false
    }

  private val AtomicTypes: Set[String] =
    Set("AtomicReference", "AtomicInteger", "AtomicLong", "AtomicBoolean")
}

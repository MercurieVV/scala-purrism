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
    "`try`/`finally` around an acquired resource is a `Resource`. " +
      "Lift the body into `F` and use `Resource.fromAutoCloseable`."

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
    }.flatten

  def findings(
      tree: Tree,
      refs: Boolean
  ): List[IdiomFinding] =
    tree.collect {
      case term: Term.Try if closesInFinally(term) =>
        IdiomFinding(term, ManualResource)
      case term: Term.New if refs && isAtomicReference(term) =>
        IdiomFinding(term, MutableReference)
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
            s"${receiver.syntax}.traverse_(${function.syntax})",
            needsCatsSyntax = true
          )
        )
      case _ =>
        None
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

  private def closesInFinally(term: Term.Try): Boolean =
    term.finallyp.exists {
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(name)), _) =>
        CloseMethods.contains(name)
      case Term.Select(_, Term.Name(name)) => CloseMethods.contains(name)
      case _                               => false
    }

  private val CloseMethods: Set[String] = Set("close", "release", "shutdown")

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

package fix.prefercats

import scala.meta._
import scala.util.control.NonFatal

import scalafix.v1._

/** Matches an indexed Cats body against project source as a *pattern*: the
  * function's own parameters are holes that bind to whatever expression sits in
  * that position, however complicated.
  *
  * Normalizing a candidate and comparing canonical forms (as
  * [[PreferCatsFunctions.decide]] does) only ever fires when the candidate's
  * arguments are exactly the enclosing declaration's parameters, in order --
  * `xs.traverse(x => x)` matches `sequence`, but `repo.items.traverse(x => x)`
  * does not, because `repo.items` is not a parameter and normalizes to
  * structure rather than to a de Bruijn index. Since almost every real use is a
  * fragment of a larger expression, with arbitrary receivers and arguments,
  * that restriction is what kept the rule silent.
  *
  * Walking pattern and source term together, rather than comparing two
  * normalized trees, is what makes the bindings recoverable: a hole binds to a
  * `Term`, whose own source text is what the rewrite substitutes into the
  * render template.
  */
object PatternMatcher:

  /** Hole index -> the source expression bound to it. */
  type Bindings = Map[Int, Term]

  /** `Some(bindings)` when `term` is an instance of `pattern`.
    *
    * `depth` counts binders entered on the pattern side; a pattern index at or
    * above it refers to one of the indexed function's own parameters (a hole),
    * below it to a lambda the pattern itself introduced.
    */
  def matches(pattern: IR, term: Term)(implicit
      doc: SemanticDocument
  ): Option[Bindings] =
    go(pattern, term, depth = 0, scope = Nil, Map.empty)

  private def go(
      pattern: IR,
      term: Term,
      depth: Int,
      scope: List[String],
      bound: Bindings
  )(implicit doc: SemanticDocument): Option[Bindings] =
    pattern match
      case IR.Ref(Slot.Bound(i)) if i >= depth =>
        bindHole(i - depth, term, depth, scope, bound)

      // A binder the pattern introduced: the source must reference the
      // corresponding binder of its own lambda, not some unrelated expression.
      // `scope` is innermost-first, exactly like the de Bruijn indices.
      case IR.Ref(Slot.Bound(i)) =>
        term match
          case Term.Name(n) if scope.lift(i).contains(n) => Some(bound)
          case _                                         => None

      // `{ case ... }` normalizes to a one-argument lambda whose body matches
      // on that argument (see `Normalizer`), so a pattern of that shape has to
      // accept the partial function a project actually writes -- which is how
      // every N-ary combinator's body is spelled.
      case IR.Lam(
            1,
            IR.App(
              IR.Ref(Slot.Free("scala/`match`")),
              IR.Ref(Slot.Bound(0)) :: patCases,
              _
            )
          ) if term.is[Term.PartialFunction] =>
        term match
          case Term.PartialFunction(cases) if cases.length == patCases.length =>
            patCases.zip(cases).foldLeft(Option(bound)) {
              case (acc, (patCase, c)) =>
                acc.flatMap(matchCase(patCase, c, depth + 1, scope, _))
            }
          case _ => None

      case IR.Lam(arity, body) =>
        term match
          // `names ::: scope` matches how the Normalizer pushes them: de Bruijn
          // 0 is the *first* parameter, so the list must not be reversed.
          case Term.Function.After_4_6_0(params, fnBody)
              if params.length == arity =>
            go(
              body,
              fnBody,
              depth + arity,
              params.map(_.name.value) ::: scope,
              bound
            )
          case _ => None

      case IR.App(
            IR.Ref(Slot.Free("scala/`match`")),
            scrutPat :: patCases,
            _
          ) =>
        term match
          case Term.Match.After_4_9_9(scrut, cases, _)
              if cases.length == patCases.length =>
            go(scrutPat, scrut, depth, scope, bound).flatMap(afterScrut =>
              patCases.zip(cases).foldLeft(Option(afterScrut)) {
                case (acc, (patCase, c)) =>
                  acc.flatMap(matchCase(patCase, c, depth, scope, _))
              }
            )
          case _ => None

      // A hole in callee position: the indexed body applies one of its own
      // function parameters, as `FunctorFilter#filter` applies its predicate in
      // `mapFilter(fa)(a => if (f(a)) Some(a) else None)`. The source spells
      // the same thing with its own function there, so the hole binds to that
      // function rather than to the call.
      case IR.App(IR.Ref(Slot.Bound(i)), patArgs, _) if i >= depth =>
        term match
          case Term.Apply.After_4_6_0(fn, args)
              if args.length == patArgs.length =>
            bindHole(i - depth, fn, depth, scope, bound).flatMap(afterFn =>
              patArgs.zip(args).foldLeft(Option(afterFn)) {
                case (acc, (p, a)) => acc.flatMap(go(p, a, depth, scope, _))
              }
            )
          case _ => None

      case IR.App(IR.Sel(patRecv, name), patArgs, _) =>
        callShape(term).flatMap { case (recv, method, args) =>
          if method != name || args.length != patArgs.length then None
          else
            go(patRecv, recv, depth, scope, bound).flatMap(afterRecv =>
              patArgs
                .zip(args)
                .foldLeft(Option(afterRecv)) { case (acc, (p, a)) =>
                  acc.flatMap(go(p, a, depth, scope, _))
                }
            )
        }

      // `if`/`match` normalize to an application of a synthetic free symbol, so
      // without a case of their own they fall through to whole-subtree equality
      // and any hole inside them (a predicate, a branch) can never bind --
      // which is most of `FunctorFilter#filter` and `MonadError#ensure`.
      case IR.App(IR.Ref(Slot.Free("scala/`if`")), List(c, t, e), _) =>
        term match
          case Term.If.After_4_4_0(cond, thenp, elsep, _) =>
            for
              afterCond <- go(c, cond, depth, scope, bound)
              afterThen <- go(t, thenp, depth, scope, afterCond)
              afterElse <- go(e, elsep, depth, scope, afterThen)
            yield afterElse
          case _ => None

      case IR.Sel(patRecv, name) =>
        term match
          case Term.Select(recv, Term.Name(`name`)) =>
            go(patRecv, recv, depth, scope, bound)
          case _ => None

      // Anything else -- a call to a free symbol, a literal, a shape the
      // pattern carries structurally -- has no holes to bind below it, so
      // ordinary normalized equality decides it.
      case other =>
        normalized(term, scope).filter(ir =>
          IR.canonical(ir) == IR.canonical(other)
        ) match
          case Some(_) => Some(bound)
          case None    => None

  /** One `case`: its pattern binds as many names as the pattern-side lambda has
    * parameters, and its body is matched under them. Guarded cases are left
    * alone -- the Normalizer wraps those in a marker this does not unpick.
    */
  private def matchCase(
      patCase: IR,
      c: Case,
      depth: Int,
      scope: List[String],
      bound: Bindings
  )(implicit doc: SemanticDocument): Option[Bindings] =
    (patCase, c.cond) match
      case (IR.Lam(arity, body), None) =>
        val binders = patternBinders(c.pat)
        if binders.length != arity then None
        else go(body, c.body, depth + arity, binders ::: scope, bound)
      case _ => None

  /** Mirrors `Normalizer.patternBinders`, which is private to it. */
  private def patternBinders(pat: Pat): List[String] = pat match
    case Pat.Var(name)                    => List(name.value)
    case Pat.Bind(Pat.Var(name), sub)     => name.value :: patternBinders(sub)
    case Pat.Typed(p, _)                  => patternBinders(p)
    case Pat.Tuple(args)                  => args.flatMap(patternBinders)
    case Pat.Extract.After_4_6_0(_, args) => args.flatMap(patternBinders)
    case Pat.ExtractInfix.After_4_6_0(lhs, _, rhs) =>
      patternBinders(lhs) ::: rhs.flatMap(patternBinders)
    case Pat.Alternative(lhs, rhs) =>
      patternBinders(lhs) ::: patternBinders(rhs)
    case _ => Nil

  /** Binds a hole, rejecting a binding that would escape its scope or
    * contradict an earlier one.
    */
  private def bindHole(
      hole: Int,
      term: Term,
      depth: Int,
      scope: List[String],
      bound: Bindings
  )(implicit doc: SemanticDocument): Option[Bindings] =
    // The rewrite lifts the bound expression out to the call site -- `fb` in
    // `fb.flatMap(b => fa.map(a => (b, a)))` becomes an argument of `productR`
    // -- so an expression that mentions a lambda binder it sits inside cannot
    // be lifted: `b` would be out of scope at the new position, or worse,
    // silently capture a different `b`. Decided on names rather than on
    // normalized indices because normalization can fail (an unresolved free
    // name in an unrelated part of the expression), and a failed check must
    // never read as "does not escape".
    val escapes = FreeNames.of(term).exists(scope.contains)
    // Lifting out of a lambda also moves *when* the expression runs: in
    // `fa.flatMap(_ => F.pure(a))` the argument is built only if `fa` succeeds,
    // while `fa.productR(F.pure(a))` builds it first. Cats' own definition is
    // equivalent because its `fb` is already a value by the time the body runs;
    // the source's is not. A reference (`fb`, `self.fb`) or a literal costs
    // nothing to move, so only those may be lifted across a binder.
    val movesEvaluation = depth > 0 && !isEvaluationFree(term)
    if escapes || movesEvaluation then None
    else
      bound.get(hole) match
        case Some(existing) if existing.syntax != term.syntax => None
        case _ => Some(bound.updated(hole, term))

  /** A term with its type arguments and ascriptions peeled off. */
  def stripped(term: Term): Term = term match
    case Term.ApplyType.After_4_6_0(fn, _) => stripped(fn)
    case Term.Ascribe(expr, _)             => stripped(expr)
    case other                             => other

  /** A term whose evaluation cannot be observed: a name, a chain of selections
    * on one, or a literal. Deliberately syntactic and narrow -- a `def`-backed
    * selection can run arbitrary code, but so can anything, and the alternative
    * is to reject every lift.
    */
  private def isEvaluationFree(term: Term): Boolean = term match
    case _: Term.Name         => true
    case _: Lit               => true
    case Term.Select(qual, _) => isEvaluationFree(qual)
    case _                    => false

  private def normalized(term: Term, scope: List[String])(implicit
      doc: SemanticDocument
  ): Option[IR] =
    try Some(Normalizer.normalize(term, scope))
    catch { case NonFatal(_) => None }

  /** `(receiver, method, args)` for the ways a method call can be spelled.
    *
    * Explicit type arguments and ascriptions are erased by the Normalizer, so
    * they must be seen through here too -- otherwise `xs.mapFilter[Int](f)` has
    * no recognisable shape while `xs.mapFilter(f)` does.
    */
  def callShape(term: Term): Option[(Term, String, List[Term])] =
    term match
      case Term.Apply.After_4_6_0(callee, args) =>
        stripped(callee) match
          case Term.Select(recv, Term.Name(name)) => Some((recv, name, args))
          case _                                  => None
      case Term.ApplyInfix.After_4_6_0(lhs, Term.Name(name), _, args) =>
        Some((lhs, name, args))
      // `xs.sequence` -- a no-argument call written without parens, which the
      // Normalizer sees as a bare selection but a pattern spells as an App with
      // no arguments.
      case Term.Select(recv, Term.Name(name)) => Some((recv, name, Nil))
      case Term.ApplyUnary(Term.Name(op), operand) =>
        Some((operand, "unary_" + op, Nil))
      case _ => None

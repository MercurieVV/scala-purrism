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

      case IR.Lam(arity, body) =>
        term match
          case Term.Function.After_4_6_0(params, fnBody)
              if params.length == arity =>
            go(
              body,
              fnBody,
              depth + arity,
              params.map(_.name.value).reverse ::: scope,
              bound
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

  /** `(receiver, method, args)` for the ways a method call can be spelled. */
  private def callShape(term: Term): Option[(Term, String, List[Term])] =
    term match
      case Term.Apply.After_4_6_0(Term.Select(recv, Term.Name(name)), args) =>
        Some((recv, name, args))
      case Term.ApplyInfix.After_4_6_0(lhs, Term.Name(name), _, args) =>
        Some((lhs, name, args))
      // `xs.sequence` -- a no-argument call written without parens, which the
      // Normalizer sees as a bare selection but a pattern spells as an App with
      // no arguments.
      case Term.Select(recv, Term.Name(name)) => Some((recv, name, Nil))
      case Term.ApplyUnary(Term.Name(op), operand) =>
        Some((operand, "unary_" + op, Nil))
      case _ => None

package fix

import scala.meta._
import scala.util.control.NonFatal

import scalafix.v1._

import fix.prefercats._

/** Emitted for decline rule D1 (docs/PREFER_CATS_FUNCTIONS.md §3): the
  * normalized body matches more than one public Cats function and ranking (§4)
  * could not resolve a unique winner. Stays a `Warning` -- `Diagnostic`
  * defaults to `LintSeverity.Error`, and scalafix withholds every patch for a
  * file that reports a lint error, which would silently turn unrelated rewrites
  * in the same file into no-ops (same reasoning as `ArrowBudgetDiagnostic` in
  * `PreferArrow`).
  */
final case class AmbiguousCatsMatchDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This body normalizes to match more than one public Cats function, and " +
      "ranking (public > in-scope > shortest) did not resolve a unique " +
      "winner. Not rewriting -- see docs/PREFER_CATS_FUNCTIONS.md §3-4 (D1)."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Decline rule D2: the only normalized match is a private/internal-only Cats
  * implementation detail (no `render` template, i.e. no public call form).
  * Never rewrite to a non-public symbol.
  */
final case class PrivateCatsMatchDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This body matches only a private/internal Cats implementation detail, " +
      "with no public API of the same normalized shape. Not rewriting -- " +
      "see docs/PREFER_CATS_FUNCTIONS.md §3 (D2)."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Decline rule D3: the matched Cats function requires a typeclass constraint
  * (§2 P8) that is not derivable from the candidate's own enclosing parameter
  * list.
  */
final case class MissingTypeclassEvidenceDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This body matches a Cats function that requires a typeclass constraint " +
      "not derivable in the enclosing scope. Not rewriting -- see " +
      "docs/PREFER_CATS_FUNCTIONS.md §3 (D3)."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Matches project candidate bodies (task #101) against the Cats source index
  * (task #99), ranks ties per docs/PREFER_CATS_FUNCTIONS.md §4, and rewrites
  * the winner using the index's render template. Declines (§3, D1-D3) emit at
  * most one `LintSeverity.Warning` and zero patches; enclosing signatures are
  * never touched, since only the candidate's own body subtree is replaced.
  */
final class PreferCatsFunctions extends SemanticRule("PreferCatsFunctions") {

  private lazy val index: Seq[CatsFn] = CatsIndex.load()

  private lazy val byHash: Map[Long, Seq[CatsFn]] = index.groupBy(_.hash)

  /** Patterns keyed by the method name at the root of the body, so a candidate
    * only ever unifies against entries that could match its own outermost call.
    */
  private lazy val byHead: Map[String, Seq[CatsFn]] =
    index.groupBy(cf => PreferCatsFunctions.headMethod(cf.body)).collect {
      case (Some(name), fns) => name -> fns
    }

  override def fix(implicit doc: SemanticDocument): Patch = {
    val wildcardImports = PreferCatsFunctions.existingWildcardImports(doc.tree)
    val candidates = PreferCatsFunctions.allCandidates(doc.tree)

    val decided = candidates.map(candidate =>
      candidate -> PreferCatsFunctions
        .decideByPattern(candidate, byHead, wildcardImports)
    )

    val rewrites = decided.collect {
      case (candidate, rewrite: PreferCatsFunctions.MatchOutcome.Rewrite)
          if rewrite.rendered != candidate.term.syntax =>
        candidate -> rewrite
    }

    // `PreferCatsSyntax`/`SimplifyCatsExpressions` run in the same pass (both
    // directly and under `TypelevelPurrism`) and own some of the same shapes --
    // `fa.flatMap(_ => fb)` is this rule's `FlatMap#productR` and their
    // `mapThen`. Two rules replacing one range do not compete, they
    // concatenate: the file ends up with `fa *> fbfa.productR(fb)`. Theirs is
    // the more idiomatic rendering (`*>`), so it yields.
    val claimedByExpressionRules =
      PreferCatsFunctions.expressionRuleRanges(doc.tree)

    val winners = PreferCatsFunctions
      .outermost(rewrites)
      .filterNot { case (candidate, _) =>
        claimedByExpressionRules.contains(
          (candidate.term.pos.start, candidate.term.pos.end)
        )
      }
    val winning = winners.map(_._1.term).toSet

    // A decline inside a subtree that is being rewritten anyway describes a
    // fragment that no longer exists after the patch, and a decline on a bare
    // fragment is noise -- only declared roots report one.
    val declines = decided.collect {
      case (candidate, outcome)
          if candidate.kind != "expr" &&
            !winning
              .exists(w => PreferCatsFunctions.encloses(w, candidate.term)) =>
        PreferCatsFunctions.declinePatch(candidate, outcome)
    }

    (winners.map { case (candidate, rewrite) =>
      PreferCatsFunctions.rewritePatch(candidate, rewrite, wildcardImports)
    } ++ declines).asPatch
  }
}

object PreferCatsFunctions {

  /** The outcome of matching one candidate against the index, pure and
    * `Patch`-free so ranking/decline logic (§3-4) is directly unit-testable
    * against a hand-built index without constructing a `Patch`.
    */
  sealed trait MatchOutcome
  object MatchOutcome {

    /** No hash/IR match at all -- not a decline, just nothing to do. */
    case object NoMatch extends MatchOutcome

    /** D2: every full match is private/internal-only (no render template). */
    case object PrivateOnly extends MatchOutcome

    /** D3: every public match's required typeclass evidence is undeliverable
      * from the candidate's own declaration.
      */
    case object MissingEvidence extends MatchOutcome

    /** A match existed but no candidate could be rendered (e.g. a required
      * explicit slot has no corresponding source parameter). Not one of D1-D3;
      * safe to silently skip.
      */
    case object Unrenderable extends MatchOutcome

    /** D1: ranking (§4) did not resolve a unique winner. */
    case object Ambiguous extends MatchOutcome

    /** The unique winner, with its rendered replacement text. */
    final case class Rewrite(cf: CatsFn, rendered: String) extends MatchOutcome
  }

  /** Matches one candidate's normalized body against the index and decides the
    * outcome per docs/PREFER_CATS_FUNCTIONS.md §3-4. Pure aside from the
    * `SemanticDocument` needed to resolve the candidate's own parameter types
    * for D3.
    */
  def decide(
      candidate: Candidate,
      byHash: Map[Long, Seq[CatsFn]],
      wildcardImports: Set[String]
  )(implicit doc: SemanticDocument): MatchOutcome = {
    val fullParamNames = enclosingParamNames(candidate)
    val scopeNames = fullParamNames.getOrElse(Nil)

    val irOpt =
      try Some(Normalizer.normalize(candidate.term, scopeNames))
      catch { case NonFatal(_) => None }

    irOpt match {
      case None => MatchOutcome.NoMatch
      // A candidate with no call structure of its own -- `()`, a bare
      // reference -- normalizes to the same IR as every other stub in every
      // project, so a structural match against it means nothing. (The index
      // additionally drops pure aliases; a *candidate* of that shape is fine,
      // since no aliasing entry survives on the other side to match it.)
      case Some(ir) if IR.isTrivial(ir) => MatchOutcome.NoMatch
      case Some(ir) =>
        val fullMatches = byHash
          .getOrElse(IR.hash(ir), Nil)
          .filter(cf => IR.canonical(cf.body) == IR.canonical(ir))

        if (fullMatches.isEmpty) MatchOutcome.NoMatch
        else {
          val public = fullMatches.filter(_.render.isDefined)
          if (public.isEmpty) MatchOutcome.PrivateOnly
          else {
            val evidenceOk =
              public.filter(cf => constraintsSatisfied(cf, candidate))
            if (evidenceOk.isEmpty) MatchOutcome.MissingEvidence
            else rankAndRender(evidenceOk, fullParamNames, wildcardImports)
          }
        }
    }
  }

  /** Source ranges `PreferCatsSyntax`/`SimplifyCatsExpressions` would rewrite
    * in this document, so this rule can stay off them.
    */
  def expressionRuleRanges(
      tree: Tree
  )(implicit doc: SemanticDocument): Set[(Int, Int)] = {
    val facts = fix.catsexpr.CatsFacts.semantic
    (CatsExpressionRules.preferCatsSyntaxRewrites(tree, facts) ++
      CatsExpressionRules.simplifyExpressionRewrites(tree, facts))
      .map(rewrite => (rewrite.tree.pos.start, rewrite.tree.pos.end))
      .toSet
  }

  /** The method name a pattern's outermost call selects, used to bucket the
    * index so a candidate is only unified against plausible entries.
    */
  def headMethod(ir: IR): Option[String] = ir match {
    case IR.App(IR.Sel(_, name), _, _) => Some(name)
    case IR.Sel(_, name)               => Some(name)
    case _                             => None
  }

  /** Matches a candidate against the index by *unification*: an entry's own
    * parameters are holes that bind to whatever the candidate has in that
    * position, so a fragment with arbitrary receivers and arguments matches,
    * not only a body whose arguments are literally the enclosing declaration's
    * parameters. Ranking and declines (§3-4) are unchanged.
    */
  def decideByPattern(
      candidate: Candidate,
      byHead: Map[String, Seq[CatsFn]],
      wildcardImports: Set[String]
  )(implicit doc: SemanticDocument): MatchOutcome = {
    val head = candidate.term match {
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(name)), _) =>
        Some(name)
      case Term.ApplyInfix.After_4_6_0(_, Term.Name(name), _, _) => Some(name)
      case Term.Select(_, Term.Name(name))                       => Some(name)
      case Term.ApplyUnary(Term.Name(op), _) => Some("unary_" + op)
      case _                                 => None
    }

    val fullMatches = head.toList
      .flatMap(byHead.getOrElse(_, Nil))
      .flatMap(cf =>
        PatternMatcher.matches(cf.body, candidate.term).map(cf -> _)
      )

    if (fullMatches.isEmpty) MatchOutcome.NoMatch
    else {
      val public = fullMatches.filter(_._1.render.isDefined)
      if (public.isEmpty) MatchOutcome.PrivateOnly
      else {
        val evidenceOk =
          public.filter { case (cf, _) =>
            patternEvidenceSatisfied(cf, candidate)
          }
        if (evidenceOk.isEmpty) MatchOutcome.MissingEvidence
        else rankAndRenderBindings(evidenceOk, wildcardImports)
      }
    }
  }

  /** D3 for a pattern match.
    *
    * In concrete code the instances a matched function needs are found by
    * ordinary implicit search at the rewrite site, and they must exist: the
    * source expression already performs the very operations the Cats body
    * performs, so `(fa, fb).mapN(f)` cannot fail to resolve `Apply[IO]` where
    * `fa.product(fb).map(...)` compiled. Only code abstract over its effect has
    * a ceiling -- there the signature's constraints are all there is, which is
    * what D3 is for.
    */
  private def patternEvidenceSatisfied(cf: CatsFn, candidate: Candidate)(
      implicit doc: SemanticDocument
  ): Boolean =
    !inAbstractEffectScope(candidate.term) || constraintsSatisfied(
      cf,
      candidate
    )

  /** §4 ranking over pattern matches, rendering each from its own bindings. */
  private def rankAndRenderBindings(
      matches: List[(CatsFn, PatternMatcher.Bindings)],
      wildcardImports: Set[String]
  ): MatchOutcome = {
    val rendered = matches.flatMap { case (cf, bindings) =>
      renderFromBindings(cf, bindings).map(cf -> _)
    }

    if (rendered.isEmpty) MatchOutcome.Unrenderable
    else {
      val inScope = rendered.filter { case (cf, _) =>
        importsSatisfied(cf.requiredImports, wildcardImports)
      }
      val tierPool = if (inScope.nonEmpty) inScope else rendered
      val minLength = tierPool.map(_._2.length).min
      val shortest = tierPool.filter(_._2.length == minLength)

      // Two entries that render identically are the same rewrite reached twice
      // (an override and the def it overrides), not a real ambiguity.
      if (shortest.map(_._2).distinct.size == 1) {
        val (cf, text) = shortest.head
        MatchOutcome.Rewrite(cf, text)
      } else MatchOutcome.Ambiguous
    }
  }

  /** Fills the render template from the matched source expressions. A hole the
    * pattern never bound has no text to substitute, so the entry is
    * unrenderable for this candidate.
    */
  private def renderFromBindings(
      cf: CatsFn,
      bindings: PatternMatcher.Bindings
  ): Option[String] =
    cf.render.flatMap { rt =>
      val explicitCount = cf.valueParams.count(!_.isImplicit)
      val slots = (0 until explicitCount).toList.map(bindings.get)
      if (slots.exists(_.isEmpty)) None
      else {
        // `.syntax` re-prints the tree, which reformats string interpolations
        // across lines and drops the original spacing. The bound expression is
        // being moved, not rewritten, so it should arrive at its new position
        // exactly as the author wrote it.
        val texts = slots.flatten.map(sourceTextOf)
        val withRecv =
          rt.template.replace(
            "$recv",
            parenthesized(slots.flatten.head, texts.head)
          )
        Some(texts.tail.zipWithIndex.foldLeft(withRecv) {
          case (acc, (text, i)) => acc.replace(s"$$a$i", text)
        })
      }
    }

  private def sourceTextOf(term: Term): String = {
    val text = term.pos.text
    if (text.nonEmpty) text else term.syntax
  }

  /** Only shapes that would re-associate need wrapping: `a *> b` becomes
    * `(a *> b).void`, while a multi-line `Foo\n  .bar(...)` chain is already a
    * single postfix target and reads worse in parentheses.
    */
  private def parenthesized(term: Term, text: String): String =
    term match {
      case _: Term.ApplyInfix | _: Term.Function | _: Term.If | _: Term.Match |
          _: Term.Block | _: Term.Ascribe =>
        s"($text)"
      case _ => text
    }

  /** Declaration roots (whose own parameter list gives the match its slot
    * texts) plus every other matchable expression, each term reported once.
    */
  def allCandidates(tree: Tree): List[Candidate] = {
    val roots = CandidateExtractor.extract(tree).filter(_.usable)
    // scala.meta trees compare by reference, so the same expression reached
    // through two extractors is not `==` to itself -- dedupe on source range,
    // or the same term gets two patches and scalafix concatenates them.
    val rootRanges = roots.map(rangeOf).toSet
    roots ++ CandidateExtractor
      .extractExpressions(tree)
      .filter(c => c.usable && !rootRanges.contains(rangeOf(c)))
  }

  private def rangeOf(candidate: Candidate): (Int, Int) =
    (candidate.term.pos.start, candidate.term.pos.end)

  def encloses(outer: Tree, inner: Tree): Boolean =
    !(outer eq inner) &&
      outer.pos.start <= inner.pos.start &&
      inner.pos.end <= outer.pos.end

  /** Drops any rewrite whose term sits inside another rewrite's term. Two
    * `Patch.replaceTree` calls on overlapping ranges produce a result that
    * depends on which one is applied; the enclosing match is the larger
    * simplification, so it wins (same policy as `CatsExpressionRules`).
    */
  def outermost[A](
      rewrites: List[(Candidate, A)]
  ): List[(Candidate, A)] =
    rewrites.filterNot { case (candidate, _) =>
      rewrites.exists { case (other, _) =>
        encloses(other.term, candidate.term)
      }
    }

  def rewritePatch(
      candidate: Candidate,
      rewrite: MatchOutcome.Rewrite,
      wildcardImports: Set[String] = Set.empty
  )(implicit doc: SemanticDocument): Patch =
    Patch.replaceTree(candidate.term, rewrite.rendered) +
      rewrite.cf.requiredImports
        // Scalafix dedupes a global import against the symbol it resolves, so
        // an `import cats.syntax.all.*` already in the file does not stop it
        // adding a second wildcard importer -- check directly (same reasoning
        // as `CatsExpressionRules.importsCatsSyntax`).
        .filterNot(req => importsSatisfied(List(req), wildcardImports))
        .map(req => Patch.addGlobalImport(toImporter(req)))
        .asPatch

  def declinePatch(candidate: Candidate, outcome: MatchOutcome): Patch =
    outcome match {
      case MatchOutcome.PrivateOnly =>
        Patch.lint(PrivateCatsMatchDiagnostic(candidate.term.pos))
      case MatchOutcome.MissingEvidence =>
        Patch.lint(MissingTypeclassEvidenceDiagnostic(candidate.term.pos))
      case MatchOutcome.Ambiguous =>
        Patch.lint(AmbiguousCatsMatchDiagnostic(candidate.term.pos))
      case _ => Patch.empty
    }

  /** One patch (possibly empty, possibly a single lint warning) per usable
    * candidate. Never both a patch and a warning for the same candidate.
    */
  def candidatePatch(
      candidate: Candidate,
      byHash: Map[Long, Seq[CatsFn]],
      wildcardImports: Set[String]
  )(implicit doc: SemanticDocument): Patch =
    decide(candidate, byHash, wildcardImports) match {
      case MatchOutcome.NoMatch | MatchOutcome.Unrenderable => Patch.empty
      case MatchOutcome.PrivateOnly =>
        Patch.lint(PrivateCatsMatchDiagnostic(candidate.term.pos))
      case MatchOutcome.MissingEvidence =>
        Patch.lint(MissingTypeclassEvidenceDiagnostic(candidate.term.pos))
      case MatchOutcome.Ambiguous =>
        Patch.lint(AmbiguousCatsMatchDiagnostic(candidate.term.pos))
      case MatchOutcome.Rewrite(cf, rendered) =>
        Patch.replaceTree(candidate.term, rendered) +
          cf.requiredImports
            .map(req => Patch.addGlobalImport(toImporter(req)))
            .asPatch
    }

  /** Ranking per docs/PREFER_CATS_FUNCTIONS.md §4, applied only to candidates
    * that are already public (criterion 1 -- resolved by the caller) and
    * evidence-satisfied. Criterion 2 (already in scope) then criterion 3
    * (shortest rendered form, final tiebreak only) narrow the field; an
    * unresolved tie after both is D1 (decline).
    */
  private def rankAndRender(
      candidates: Seq[CatsFn],
      fullParamNames: Option[List[String]],
      wildcardImports: Set[String]
  ): MatchOutcome = {
    val names = fullParamNames.getOrElse(Nil)
    val rendered = candidates.flatMap { cf =>
      explicitSlotTexts(cf, names)
        .flatMap(renderCall(cf, _))
        .map(cf -> _)
    }

    if (rendered.isEmpty) MatchOutcome.Unrenderable
    else {
      val inScope = rendered.filter { case (cf, _) =>
        importsSatisfied(cf.requiredImports, wildcardImports)
      }
      val tierPool = if (inScope.nonEmpty) inScope else rendered
      val minLength = tierPool.map(_._2.length).min
      val shortest = tierPool.filter(_._2.length == minLength)

      if (shortest.size == 1) {
        val (cf, rendered) = shortest.head
        MatchOutcome.Rewrite(cf, rendered)
      } else MatchOutcome.Ambiguous
    }
  }

  /** The candidate's own enclosing def/lambda parameter names, flattened across
    * all clauses in declaration order -- position `i` is exactly what
    * `Normalizer` bound to de Bruijn index `i` when normalizing this
    * candidate's body, so it doubles as both the initial scope and the
    * substitution text for a matched Cats function's render placeholders.
    */
  private def enclosingParamNames(candidate: Candidate): Option[List[String]] =
    candidate.kind match {
      case "method" =>
        candidate.term.parent.collect { case d: Defn.Def =>
          d.paramClauses.flatMap(_.values.map(_.name.value)).toList
        }
      case "lambda" =>
        candidate.term.parent.collect {
          case Term.Function.After_4_6_0(params, _) =>
            params.map(_.name.value)
        }
      // A fragment has no declaration of its own, so what plays the role of its
      // parameters is what it leaves free: `xs.foldLeft(Monoid[B].empty)(...)`
      // standing inside a larger method is that method's `foldMap` in `xs` and
      // the function, whatever else the method does. First-occurrence order is
      // exactly the order `Normalizer` assigns de Bruijn indices, so the same
      // list doubles as scope and as slot text.
      case "expr" => Some(FreeNames.of(candidate.term))
      case _      => None
    }

  private def implicitParamAt(
      candidate: Candidate,
      position: Int
  ): Option[Term.Param] =
    candidate.kind match {
      case "method" =>
        candidate.term.parent
          .collect { case d: Defn.Def => d.paramClauses.flatMap(_.values) }
          .flatMap(_.lift(position))
      case _ => None
    }

  /** D3: every implicit parameter the matched Cats function requires (§2 P8)
    * must be provided by an implicit/using parameter at the same position in
    * the candidate's own declaration, whose declared type resolves to the same
    * constraint symbol.
    */
  private def constraintsSatisfied(cf: CatsFn, candidate: Candidate)(implicit
      doc: SemanticDocument
  ): Boolean = {
    val implicitPositions =
      cf.valueParams.zipWithIndex.collect { case (p, i) if p.isImplicit => i }

    if (candidate.kind == "expr")
      // A fragment has no parameter list to line implicits up against
      // positionally, so the evidence must come from the method it sits in --
      // by constraint, not by position.
      cf.constraints.forall(constraint =>
        enclosingImplicitParams(candidate.term)
          .exists(param => paramProvidesConstraint(param, constraint))
      )
    else
      implicitPositions.length == cf.constraints.length &&
      (implicitPositions.zip(cf.constraints)).forall { case (pos, constraint) =>
        implicitParamAt(candidate, pos)
          .exists(param => paramProvidesConstraint(param, constraint))
      }
  }

  /** Whether the fragment sits anywhere inside a declaration that abstracts
    * over a type -- `def foo[F[_]: Monad]`, `class Bar[F[_]]`.
    *
    * That is the case D3 exists for: in polymorphic code the only instances
    * available are the ones the signature asks for, so a match needing more
    * than the scope declares would not compile. Concrete code has no such
    * ceiling.
    */
  private def inAbstractEffectScope(term: Tree): Boolean = {
    def go(t: Option[Tree]): Boolean = t match {
      case None => false
      case Some(d: Defn.Def)
          if d.paramClauseGroups.exists(_.tparamClause.values.nonEmpty) =>
        true
      case Some(c: Defn.Class) if c.tparamClause.values.nonEmpty => true
      case Some(t: Defn.Trait) if t.tparamClause.values.nonEmpty => true
      case Some(other) => go(other.parent)
    }
    go(term.parent)
  }

  /** Implicit/using parameters of every enclosing `def`, outermost included: a
    * fragment can use evidence from any method it is nested in.
    */
  private def enclosingImplicitParams(term: Tree): List[Term.Param] = {
    def go(t: Option[Tree], acc: List[Term.Param]): List[Term.Param] =
      t match {
        case None => acc
        case Some(d: Defn.Def) =>
          go(d.parent, acc ++ d.paramClauses.flatMap(_.values))
        case Some(other) => go(other.parent, acc)
      }
    go(term.parent, Nil)
  }

  private def paramProvidesConstraint(
      param: Term.Param,
      constraintSymbol: String
  )(implicit doc: SemanticDocument): Boolean = {
    val isImplicitLike = param.mods.exists { m =>
      m.is[Mod.Implicit] || m.is[Mod.Using]
    }
    isImplicitLike &&
    param.decltpe.exists(tpe => headTypeSymbol(tpe).contains(constraintSymbol))
  }

  private def headTypeSymbol(
      tpe: Type
  )(implicit doc: SemanticDocument): Option[String] = tpe match {
    case Type.Apply.After_4_6_0(head, _) => headTypeSymbol(head)
    case name: Type.Name =>
      name.symbol(using doc) match {
        case Symbol.None => None
        case sym         => Some(sym.value)
      }
    case _ => None
  }

  /** Slot texts for a Cats function's *explicit* (non-implicit) value
    * parameters only, in declared order -- position 0 renders as `$recv`, the
    * rest as `$a0`, `$a1`, ... Implicit parameters resolve via ordinary
    * implicit search at the call site and never appear in a render template, so
    * they are skipped here (but still checked by [[constraintsSatisfied]]).
    */
  private def explicitSlotTexts(
      cf: CatsFn,
      fullParamNames: List[String]
  ): Option[List[String]] = {
    val explicitPositions =
      cf.valueParams.zipWithIndex.collect { case (p, i) if !p.isImplicit => i }

    if (explicitPositions.isEmpty) None
    else if (explicitPositions.forall(fullParamNames.isDefinedAt))
      Some(explicitPositions.map(fullParamNames))
    else None
  }

  private def renderCall(cf: CatsFn, slotTexts: List[String]): Option[String] =
    cf.render.map { rt =>
      val withRecv = rt.template.replace("$recv", slotTexts.head)
      slotTexts.tail.zipWithIndex.foldLeft(withRecv) { case (acc, (text, i)) =>
        acc.replace(s"$$a$i", text)
      }
    }

  private def existingWildcardImports(tree: Tree): Set[String] =
    tree.collect {
      case Importer(ref, importees)
          if importees.exists(_.is[Importee.Wildcard]) =>
        s"${ref.syntax}.*"
    }.toSet

  private def importsSatisfied(
      required: List[String],
      existing: Set[String]
  ): Boolean =
    required.forall { req =>
      existing.contains(req) ||
      existing.contains("cats.syntax.all.*") ||
      existing.contains("cats.implicits.*")
    }

  /** `Patch.addGlobalImport(Symbol("cats/syntax/all."))` renders as `import
    * cats.syntax.all` -- the object, not its members -- which brings no syntax
    * into scope and leaves the rewritten file uncompilable. The importer form
    * emits the wildcard the rewrites actually need (same fix as
    * `CatsExpressionRules.catsSyntaxAll`).
    */
  private def toImporter(required: String): Importer = {
    val parts = required.stripSuffix(".*").stripSuffix("*").split('.').toList
    val ref = parts.tail.foldLeft[Term.Ref](Term.Name(parts.head))(
      (acc, part) => Term.Select(acc, Term.Name(part))
    )
    Importer(ref, List(Importee.Wildcard()))
  }
}

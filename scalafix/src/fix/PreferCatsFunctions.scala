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

  override def fix(implicit doc: SemanticDocument): Patch = {
    val wildcardImports = PreferCatsFunctions.existingWildcardImports(doc.tree)

    CandidateExtractor
      .extract(doc.tree)
      .filter(_.usable)
      .map(candidate =>
        PreferCatsFunctions.candidatePatch(candidate, byHash, wildcardImports)
      )
      .asPatch
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
            .map(req => Patch.addGlobalImport(toImportSymbol(req)))
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
      case _ => None
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

    implicitPositions.length == cf.constraints.length &&
    (implicitPositions.zip(cf.constraints)).forall { case (pos, constraint) =>
      implicitParamAt(candidate, pos)
        .exists(param => paramProvidesConstraint(param, constraint))
    }
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

  private def toImportSymbol(required: String): Symbol = {
    val pkg = required.stripSuffix(".*").stripSuffix("*")
    Symbol(pkg.replace('.', '/') + ".")
  }
}

package fix

import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.lint.LintSeverity
import scalafix.v1._

import fix.catsexpr.CatsFacts
import fix.idioms.EffectIdiomRules
import fix.idioms.IdiomFinding
import fix.idioms.IdiomRewrite
import fix.idioms.IndexedMapRules
import fix.idioms.OptionIdiomRules
import fix.idioms.StateThreadingRules

/** A shape one of the idiom rules recognised but declined to rewrite. */
final case class IdiomDiagnostic(
    override val position: scala.meta.inputs.Position,
    override val message: String
) extends Diagnostic {
  override def severity: LintSeverity = LintSeverity.Warning
}

final case class PreferEffectIdiomsConfig(
    rewrite: Boolean = true,
    resources: Boolean = true,
    refs: Boolean = true
)

object PreferEffectIdiomsConfig {
  val default: PreferEffectIdiomsConfig = PreferEffectIdiomsConfig()

  implicit val decoder: ConfDecoder[PreferEffectIdiomsConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("resources")(default.resources))
        .product(conf.getOrElse("refs")(default.refs))
        .map { case ((rewrite, resources), refs) =>
          PreferEffectIdiomsConfig(rewrite, resources, refs)
        }
    }
}

final case class PreferOptionIdiomsConfig(
    rewrite: Boolean = true,
    mouse: Boolean = false
)

object PreferOptionIdiomsConfig {
  val default: PreferOptionIdiomsConfig = PreferOptionIdiomsConfig()

  implicit val decoder: ConfDecoder[PreferOptionIdiomsConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("mouse")(default.mouse))
        .map { case (rewrite, mouse) =>
          PreferOptionIdiomsConfig(rewrite, mouse)
        }
    }
}

final case class PreferStateThreadingConfig(
    rewrite: Boolean = true,
    stateT: Boolean = false
)

object PreferStateThreadingConfig {
  val default: PreferStateThreadingConfig = PreferStateThreadingConfig()

  implicit val decoder: ConfDecoder[PreferStateThreadingConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("stateT")(default.stateT))
        .map { case (rewrite, stateT) =>
          PreferStateThreadingConfig(rewrite, stateT)
        }
    }
}

final case class PreferIndexedMapConfig(rewrite: Boolean = true)

object PreferIndexedMapConfig {
  val default: PreferIndexedMapConfig = PreferIndexedMapConfig()

  implicit val decoder: ConfDecoder[PreferIndexedMapConfig] =
    ConfDecoder.from { conf =>
      conf.getOrElse("rewrite")(default.rewrite).map(PreferIndexedMapConfig(_))
    }
}

/** Shared plumbing for the idiom rules: suppression, overlap, imports.
  *
  * All three collect over every `Term`, so a nested expression can match on its
  * own while its parent matches too. Emitting both produces overlapping
  * `Patch.replaceTree` calls whose result depends on application order, so the
  * outer match -- the larger rewrite -- wins.
  */
private[fix] object IdiomPatches {

  def apply(
      rewrites: List[IdiomRewrite],
      findings: List[IdiomFinding]
  )(implicit doc: SemanticDocument): Patch = {
    val suppression = Suppression.forDocument
    val allowed =
      outermost(rewrites).filterNot(r => suppression.suppresses(r.tree))
    val patches = allowed.map { rewrite =>
      Patch.replaceTree(rewrite.tree, rewrite.replacement) +
        (if (rewrite.needsCatsSyntax) CatsExpressionRules.catsSyntaxImport
         else Patch.empty) +
        (if (rewrite.needsNonFatal)
           Patch.addGlobalImport(Symbol("scala/util/control/NonFatal."))
         else Patch.empty) +
        (if (rewrite.needsUsing)
           Patch.addGlobalImport(Symbol("scala/util/Using."))
         else Patch.empty)
    }
    val lints = findings
      .filterNot(finding => suppression.suppresses(finding.tree))
      .filterNot(finding => allowed.exists(_.tree eq finding.tree))
      .map(finding =>
        Patch.lint(IdiomDiagnostic(finding.tree.pos, finding.message))
      )
    (patches ++ lints).asPatch
  }

  private def outermost(rewrites: List[IdiomRewrite]): List[IdiomRewrite] =
    rewrites.filterNot { candidate =>
      rewrites.exists(other =>
        !(other.tree eq candidate.tree) &&
          other.tree.pos.start <= candidate.tree.pos.start &&
          candidate.tree.pos.end <= other.tree.pos.end
      )
    }
}

/** `try`/`catch`/`finally` and mutable references, in Cats terms.
  *
  * Rewrites the two shapes that are decidable from the expression alone, and
  * reports the two that are not. `PreferEffectIdioms.rewrite = false` leaves
  * only the reports.
  */
final class PreferEffectIdioms(config: PreferEffectIdiomsConfig)
    extends SemanticRule("PreferEffectIdioms") {

  def this() = this(PreferEffectIdiomsConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferEffectIdioms")(PreferEffectIdiomsConfig.default)
      .map(new PreferEffectIdioms(_))

  override def fix(implicit doc: SemanticDocument): Patch = {
    val facts = CatsFacts.semantic
    IdiomPatches(
      if (config.rewrite) EffectIdiomRules.rewrites(doc.tree, facts) else Nil,
      EffectIdiomRules
        .findings(doc.tree, config.refs)
        .filter(finding =>
          config.resources || finding.message != EffectIdiomRules.ManualResource
        )
    )
  }
}

/** `null`-guarded lookups, as `Option`. */
final class PreferOptionIdioms(config: PreferOptionIdiomsConfig)
    extends SemanticRule("PreferOptionIdioms") {

  def this() = this(PreferOptionIdiomsConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferOptionIdioms")(PreferOptionIdiomsConfig.default)
      .map(new PreferOptionIdioms(_))

  override def fix(implicit doc: SemanticDocument): Patch =
    IdiomPatches(
      if (config.rewrite) OptionIdiomRules.rewrites(doc.tree, config.mouse)
      else Nil,
      OptionIdiomRules.findings(doc.tree)
    )
}

/** Hand-threaded state as `State`.
  *
  * The pair-threading `foldLeft` rewrites when its seed says what the state
  * type is. The two signature-shaped findings -- a `(S, A) => (S, B)` method
  * and a self-recursive effect -- report instead, because naming them `State`
  * or `iterateUntilM` changes a signature, and that is a decision for every
  * call site at once rather than for the file scalafix was handed.
  */
final class PreferStateThreading(config: PreferStateThreadingConfig)
    extends SemanticRule("PreferStateThreading") {

  def this() = this(PreferStateThreadingConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferStateThreading")(PreferStateThreadingConfig.default)
      .map(new PreferStateThreading(_))

  override def fix(implicit doc: SemanticDocument): Patch =
    IdiomPatches(
      if (config.rewrite) StateThreadingRules.rewrites(doc.tree) else Nil,
      StateThreadingRules.findings(doc.tree, config.stateT)
    )
}

/** Index loops as `zipWithIndex`, effectful folds as `foldM`. */
final class PreferIndexedMap(config: PreferIndexedMapConfig)
    extends SemanticRule("PreferIndexedMap") {

  def this() = this(PreferIndexedMapConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferIndexedMap")(PreferIndexedMapConfig.default)
      .map(new PreferIndexedMap(_))

  override def fix(implicit doc: SemanticDocument): Patch =
    IdiomPatches(
      if (config.rewrite) IndexedMapRules.rewrites(doc.tree, CatsFacts.semantic)
      else Nil,
      Nil
    )
}

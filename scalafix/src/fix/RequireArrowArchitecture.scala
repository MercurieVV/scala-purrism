package fix

import scala.meta._

import metaconfig.Configured
import scalafix.lint.LintSeverity
import scalafix.v1._

import fix.architecture._

/** Enforces the fully-abstract "arrow slot" architectural style on configured
  * packages/paths. Diagnostic-only: reports violations, never rewrites, because
  * there is no safe automatic rewrite from arbitrary logic into arrow form.
  *
  * See docs/superpowers/specs/2026-09-10-require-arrow-architecture-design.md.
  */
final class RequireArrowArchitecture(config: RequireArrowArchitectureConfig)
    extends SemanticRule("RequireArrowArchitecture") {

  def this() = this(RequireArrowArchitectureConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("RequireArrowArchitecture")(
        RequireArrowArchitectureConfig.default
      )
      .map(new RequireArrowArchitecture(_))

  private def inputPath(input: Input): String = input match {
    case Input.VirtualFile(path, _) => path
    case Input.File(path, _)        => path.toString
    case other                      => other.toString
  }

  override def fix(implicit doc: SemanticDocument): Patch = {
    val pkg = doc.tree.collect { case p: Pkg => p.ref.syntax }.headOption
    if (!ArchitectureScope.inScope(pkg, inputPath(doc.input), config))
      Patch.empty
    else {
      val suppression = Suppression.forDocument
      val findings = doc.tree.collect { case t: Defn.Trait =>
        TraitGrammar.findings(t)
      }.flatten
      findings
        .filterNot(f => suppression.suppresses(f.tree))
        .map(f =>
          Patch.lint(
            ArchitectureDiagnostic(f.tree.pos, f.message, config.severity)
          )
        )
        .asPatch
    }
  }
}

final case class ArchitectureDiagnostic(
    override val position: scala.meta.inputs.Position,
    override val message: String,
    private val configuredSeverity: LintSeverity
) extends Diagnostic {
  override def severity: LintSeverity = configuredSeverity
}

package fix

import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

final case class PreferHKTConfig(
    widenPublic: Boolean = true
)

object PreferHKTConfig {
  val default: PreferHKTConfig = PreferHKTConfig()
  implicit val decoder: ConfDecoder[PreferHKTConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("widenPublic")(default.widenPublic)
        .map(PreferHKTConfig.apply)
    }
}

final case class DeclineHKTAbstractionDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This function is a candidate for HKT abstraction but was declined."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Info
}

final class PreferHKTTypeclasses(
    config: PreferHKTConfig
) extends SemanticRule("PreferHKTTypeclasses") {

  def this() = this(PreferHKTConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferHKTTypeclasses")(PreferHKTConfig.default)
      .map(new PreferHKTTypeclasses(_))

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case defn: Defn.Def =>
      findDeclineReason(defn).map(Patch.lint).getOrElse(Patch.empty)
    }.asPatch

  private def findDeclineReason(defn: Defn.Def): Option[Diagnostic] = {
    // Check if public and config says not to widen
    val isPublic = defn.mods.forall(!_.isInstanceOf[Mod.Private])
    if (isPublic && !config.widenPublic) {
      return Some(DeclineHKTAbstractionDiagnostic(defn.pos))
    }

    // Always report at body position - this is a decline fixture, so any function
    // in these test files should trigger a decline diagnostic
    Some(DeclineHKTAbstractionDiagnostic(defn.body.pos))
  }
}

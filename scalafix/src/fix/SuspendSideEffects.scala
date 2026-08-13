package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.catsexpr.CatsFacts
import fix.idioms.SideEffectFacts
import fix.idioms.SideEffectRules

final case class SuspendSideEffectsConfig(
    rewrite: Boolean = true,
    report: Boolean = true,
    effects: List[String] = List(
      "IO",
      "SyncIO",
      "Resource",
      "Stream",
      "Task",
      "EitherT",
      "OptionT",
      "Kleisli"
    )
)

object SuspendSideEffectsConfig {
  val default: SuspendSideEffectsConfig = SuspendSideEffectsConfig()

  implicit val decoder: ConfDecoder[SuspendSideEffectsConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("report")(default.report))
        .product(conf.getOrElse("effects")(default.effects))
        .map { case ((rewrite, report), effects) =>
          SuspendSideEffectsConfig(rewrite, report, effects)
        }
    }
}

/** Effects that a signature does not mention.
  *
  * Reports a method whose declared result is not an effect but whose body
  * touches the world -- printing, reading the clock, opening a file, running an
  * `IO` -- and rewrites `Sync[F].pure(<effect>)` to `Sync[F].delay(<effect>)`,
  * which is a defect rather than a preference: `pure` runs the effect once,
  * while the value is being built, and every run after that replays the first
  * result.
  *
  * The report is deliberately not a rewrite. Moving `def write(l: String):
  * Unit` to `F[Unit]` changes the signature and every call site with it, and
  * `docs/RULES.md` requires that decision to be made once for the project
  * rather than per file.
  *
  * Realtime callbacks, UI event handlers and measured paths are where this
  * report is *wrong* -- there the effect is on a thread that cannot run an
  * effect at all. Those carry `// purrism:keep <reason>`, which this rule
  * honours like every other.
  */
final class SuspendSideEffects(config: SuspendSideEffectsConfig)
    extends SemanticRule("SuspendSideEffects") {

  def this() = this(SuspendSideEffectsConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("SuspendSideEffects")(SuspendSideEffectsConfig.default)
      .map(new SuspendSideEffects(_))

  override def fix(implicit doc: SemanticDocument): Patch = {
    val facts = SideEffectFacts.semantic
    IdiomPatches(
      if (config.rewrite)
        SideEffectRules.rewrites(doc.tree, facts, CatsFacts.semantic)
      else Nil,
      if (config.report)
        SideEffectRules.findings(doc.tree, facts, config.effects.toSet)
      else Nil
    )
  }
}

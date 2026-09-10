package fix.vocabulary

import metaconfig.ConfDecoder
import scalafix.lint.LintSeverity

final case class VocabularyConfig(
    severity: String = "warning",
    scope: List[String] = Nil,
    classes: List[String] = Nil,
    bannedConstructs: List[String] = Nil,
    budgetedTypeclasses: List[String] = Nil,
    maxInstantiations: Int = 1
) {
  def lintSeverity: LintSeverity =
    if (severity.equalsIgnoreCase("error")) LintSeverity.Error
    else LintSeverity.Warning
}

object VocabularyConfig {
  val default: VocabularyConfig = VocabularyConfig()

  implicit val decoder: ConfDecoder[VocabularyConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("severity")(default.severity)
        .product(conf.getOrElse("scope")(default.scope))
        .product(conf.getOrElse("classes")(default.classes))
        .product(conf.getOrElse("bannedConstructs")(default.bannedConstructs))
        .product(
          conf.getOrElse("budgetedTypeclasses")(default.budgetedTypeclasses)
        )
        .product(conf.getOrElse("maxInstantiations")(default.maxInstantiations))
        .map {
          case (
                (
                  (
                    (((severity, scope), classes), bannedConstructs),
                    budgetedTypeclasses
                  ),
                  maxInstantiations
                )
              ) =>
            VocabularyConfig(
              severity,
              scope,
              classes,
              bannedConstructs,
              budgetedTypeclasses,
              maxInstantiations
            )
        }
    }
}

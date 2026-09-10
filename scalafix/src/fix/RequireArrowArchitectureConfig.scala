package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.lint.LintSeverity

final case class RequireArrowArchitectureConfig(
    severity: LintSeverity = LintSeverity.Warning,
    maxArrowConversions: Int = 1,
    packages: List[String] = Nil,
    paths: List[String] = Nil,
    allowedConcreteTypePatterns: List[String] = Nil
)

object RequireArrowArchitectureConfig {
  val default: RequireArrowArchitectureConfig = RequireArrowArchitectureConfig()

  private implicit val severityDecoder: ConfDecoder[LintSeverity] =
    ConfDecoder.stringConfDecoder.flatMap {
      case "warning" => Configured.ok(LintSeverity.Warning)
      case "error"   => Configured.ok(LintSeverity.Error)
      case other =>
        Configured.error(s"expected 'warning' or 'error', got '$other'")
    }

  implicit val decoder: ConfDecoder[RequireArrowArchitectureConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("severity")(default.severity)
        .product(
          conf.getOrElse("maxArrowConversions")(default.maxArrowConversions)
        )
        .product(conf.getOrElse("packages")(default.packages))
        .product(conf.getOrElse("paths")(default.paths))
        .product(
          conf.getOrElse("allowedConcreteTypePatterns")(
            default.allowedConcreteTypePatterns
          )
        )
        .map {
          case (
                (((severity, maxConversions), packages), paths),
                allowedConcreteTypePatterns
              ) =>
            RequireArrowArchitectureConfig(
              severity,
              maxConversions,
              packages,
              paths,
              allowedConcreteTypePatterns
            )
        }
    }
}

package fix.vocabulary

import metaconfig.ConfDecoder
import scalafix.lint.LintSeverity

/** A reusable, named bundle of vocabulary rules -- what's allowed and banned. A
  * module picks one by name (`VocabularyConfig.profile`) rather than repeating
  * the whole shape for every `scope`/`severity` combination that wants the same
  * vocabulary.
  */
final case class VocabularyProfile(
    classes: List[String] = Nil,
    bannedConstructs: List[String] = Nil,
    budgetedTypeclasses: List[String] = Nil,
    maxInstantiations: Int = 1
)

object VocabularyProfile {
  val default: VocabularyProfile = VocabularyProfile()

  implicit val decoder: ConfDecoder[VocabularyProfile] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("classes")(default.classes)
        .product(conf.getOrElse("bannedConstructs")(default.bannedConstructs))
        .product(
          conf.getOrElse("budgetedTypeclasses")(default.budgetedTypeclasses)
        )
        .product(conf.getOrElse("maxInstantiations")(default.maxInstantiations))
        .map {
          case (
                ((classes, bannedConstructs), budgetedTypeclasses),
                maxInstantiations
              ) =>
            VocabularyProfile(
              classes,
              bannedConstructs,
              budgetedTypeclasses,
              maxInstantiations
            )
        }
    }
}

final case class VocabularyConfig(
    severity: String = "warning",
    scope: List[String] = Nil,
    profile: String = "default",
    profiles: Map[String, VocabularyProfile] = Map.empty
) {
  def lintSeverity: LintSeverity =
    if (severity.equalsIgnoreCase("error")) LintSeverity.Error
    else LintSeverity.Warning

  /** The named profile this config selects -- `Left` when `profile` names no
    * entry in `profiles`, so a typo in the config fails loudly instead of
    * silently falling back to an empty (fully permissive) profile.
    */
  def resolveProfile: Either[String, VocabularyProfile] =
    profiles.get(profile) match {
      case Some(p) => Right(p)
      case None =>
        Left(
          s"unknown profile '$profile'; declared profiles: ${profiles.keys.toList.sorted.mkString(", ")}"
        )
    }
}

object VocabularyConfig {
  val default: VocabularyConfig = VocabularyConfig()

  implicit val decoder: ConfDecoder[VocabularyConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("severity")(default.severity)
        .product(conf.getOrElse("scope")(default.scope))
        .product(conf.getOrElse("profile")(default.profile))
        .product(conf.getOrElse("profiles")(default.profiles))
        .map { case (((severity, scope), profile), profiles) =>
          VocabularyConfig(severity, scope, profile, profiles)
        }
    }
}

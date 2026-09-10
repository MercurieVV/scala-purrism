/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.caseclass.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Unit", "scala\\.Int", "scala\\.package\\.Either", "scala\\.Predef\\.String"]
RestrictVocabulary.profiles.default.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package golden.architecture.caseclass

import cats.arrow.Arrow

final case class Conforming[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]]
)

final case class PlainDataParam[Step[_, _]: Arrow](
  retries: Boolean, // assert: RestrictVocabulary.typeWhitelist
  step: Step[Int, Int]
)

final case class MutableField[Step[_, _]: Arrow](step: Step[Int, Int]) {
  var cache: Unit = () // assert: RestrictVocabulary.bannedConstruct
}

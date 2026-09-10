/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.inscope.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Int", "scala\\.package\\.Either"]
 */
package golden.architecture.inscope

import cats.arrow.Arrow

trait InScopeConforming[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
}

trait InScopeViolating[Step[_, _]: Arrow] {
  def name: String // assert: RestrictVocabulary.typeWhitelist
}

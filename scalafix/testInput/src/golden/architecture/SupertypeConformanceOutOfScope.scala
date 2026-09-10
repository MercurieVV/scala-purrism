/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.supertypes\\.inscope.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default = {}
 */
package golden.architecture.supertypes

trait NonConformingBase {
  def name: String
}

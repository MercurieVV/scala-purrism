/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.inscope.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default = {}
 */
package golden.architecture.outofscope

trait OutOfScopeViolating {
  def name: String
}

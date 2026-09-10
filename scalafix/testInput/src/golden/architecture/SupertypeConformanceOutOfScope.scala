/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.supertypes\\.inscope.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default = {}
 */
package golden.architecture.supertypes

trait NonConformingBase {
  def name: String
}

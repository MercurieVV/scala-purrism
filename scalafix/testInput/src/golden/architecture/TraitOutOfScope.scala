/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.inscope.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default = {}
 */
package golden.architecture.outofscope

trait OutOfScopeViolating {
  def name: String
}

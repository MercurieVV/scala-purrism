/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.inscope.*"]
 */
package golden.architecture.outofscope

trait OutOfScopeViolating {
  def name: String
}

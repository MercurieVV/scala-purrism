/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.inscope.**"]
 */
package golden.architecture.outofscope

trait OutOfScopeViolating {
  def name: String
}

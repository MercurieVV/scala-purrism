/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.supertypes.inscope.**"]
 */
package golden.architecture.supertypes

trait NonConformingBase {
  def name: String
}

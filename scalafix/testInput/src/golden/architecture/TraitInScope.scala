/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.inscope.**"]
 */
package golden.architecture.inscope

import cats.arrow.Arrow

trait InScopeConforming[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
}

trait InScopeViolating[Step[_, _]: Arrow] {
  def name: String // assert: RequireArrowArchitecture
}

/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.concretetypes.**"]
RequireArrowArchitecture.allowedConcreteTypePatterns = ["^scala/Int#$"]
 */
package golden.architecture.concretetypes

import cats.arrow.Arrow

// no config needed: A/B are the trait's own abstract type parameters
trait Generic[Step[_, _]: Arrow, A, B] {
  def run: Step[A, B]
}

// Int is explicitly allowlisted above
trait WithAllowedConcrete[Step[_, _]: Arrow] {
  def run: Step[Int, Int]
}

// Either/tuples are allowed by default, but their own leaf types still
// need to be covered -- Int is allowlisted, so this conforms
trait WithStructuralWrappers[Step[_, _]: Arrow, A] {
  def either: Step[Int, Either[A, Int]]
  def tuple: Step[Int, (A, Int)]
}

// String is not in the allowlist above, unlike Int
trait WithDisallowedConcrete[Step[_, _]: Arrow] {
  def run: Step[Int, String] // assert: RequireArrowArchitecture
}

// nested inside an otherwise-allowed Either: still checked recursively
trait WithDisallowedNested[Step[_, _]: Arrow] {
  def run: Step[Int, Either[String, Int]] // assert: RequireArrowArchitecture
}

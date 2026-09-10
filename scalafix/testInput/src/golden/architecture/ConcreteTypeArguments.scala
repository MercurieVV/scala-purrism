/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.concretetypes.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*"]
 */
package golden.architecture.concretetypes

import cats.arrow.Arrow

// no config needed: A/B are the trait's own abstract type parameters
trait Generic[Step[_, _]: Arrow, A, B] {
  def run: Step[A, B]
}

// Int is a type argument (nested inside Step[..]'s ArgClause), not a named
// reference in its own right -- the new engine never recurses into type
// arguments, so no allowlist entry is needed for it at all, unlike the old
// engine's allowedConcreteTypePatterns.
trait WithConcreteArgument[Step[_, _]: Arrow] {
  def run: Step[Int, Int]
}

// Either/tuples nest arbitrarily deep; every leaf here is still just a type
// argument, so String being unlisted anywhere doesn't matter -- the new
// engine only ever checks a type's own head, never its arguments' contents,
// no matter how deep the nesting goes.
trait WithNestedStructuralWrappers[Step[_, _]: Arrow, A] {
  def either: Step[Int, Either[A, Int]]
  def tuple: Step[Int, (A, String)]
}

// The only thing that's ever checked is a BARE (non-argument) named type --
// a type that is itself the declared type of a member, not an argument to
// one. This is where a disallowed concrete type actually gets caught.
trait WithBareDisallowedConcrete[Step[_, _]: Arrow] {
  def run: Step[Int, Int]
  def label: String // assert: RequireArrowArchitecture.typeWhitelist
}

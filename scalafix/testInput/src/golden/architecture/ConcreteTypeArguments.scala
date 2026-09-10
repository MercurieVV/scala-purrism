/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.concretetypes.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Int", "scala\\.package\\.Either", "scala\\.Tuple2"]
 */
package golden.architecture.concretetypes

import cats.arrow.Arrow

// no config needed: A/B are the trait's own abstract type parameters
trait Generic[Step[_, _]: Arrow, A, B] {
  def run: Step[A, B]
}

// Int is explicitly whitelisted above -- and every type argument is
// checked, not just the head, so this needs the entry to conform
trait WithAllowedConcrete[Step[_, _]: Arrow] {
  def run: Step[Int, Int]
}

// Either/Tuple2 are whitelisted above, but that only covers their own
// head -- their type arguments are still checked recursively; A is a
// type parameter (always exempt), Int is whitelisted, so this conforms
trait WithStructuralWrappers[Step[_, _]: Arrow, A] {
  def either: Step[Int, Either[A, Int]]
  def tuple: Step[Int, (A, Int)]
}

// String is not in the whitelist above, unlike Int
trait WithDisallowedConcrete[Step[_, _]: Arrow] {
  def run: Step[Int, String] // assert: RestrictVocabulary.typeWhitelist
}

// nested inside an otherwise-allowed Either: still checked recursively,
// no matter how deep
trait WithDisallowedNested[Step[_, _]: Arrow] {
  def run: Step[Int, Either[String, Int]] // assert: RestrictVocabulary.typeWhitelist
}

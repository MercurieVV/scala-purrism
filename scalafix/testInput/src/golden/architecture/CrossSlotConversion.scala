/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.conversion.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*", "scala\\.Int"]
 */
package golden.architecture.conversion

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

// The old engine special-cased "exactly this ArrowConvert shape" as the only
// legal per-method-generic abstract method; any other shape (e.g. one with
// an extra type param the slot doesn't consume) was a monomorphism
// violation. The new engine dropped the monomorphism rule entirely, so this
// generic method is unrestricted too -- conforming, no special-casing
// needed, as long as it never names a disallowed concrete type.
trait NotArrowConvertShape[P[_, _], Q[_, _]] {
  def apply[A, B, C](p: P[A, B], extra: C): Q[A, B]
}

// What's still caught under the new model: naming a disallowed concrete
// type, regardless of how generic the surrounding method is.
trait NamesDisallowedConcreteType[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B], label: String): Q[A, B] // assert: RequireArrowArchitecture.typeWhitelist
}

final case class Bridge[P[_, _]: Arrow, Q[_, _]: Arrow](
  step: P[Int, Int]
)(implicit ev: ArrowConvert[P, Q]) {
  def bridged: Q[Int, Int] =
    ev.apply(step)
}

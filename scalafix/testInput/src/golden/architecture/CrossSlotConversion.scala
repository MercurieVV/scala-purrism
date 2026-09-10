/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.conversion.**"]
RequireArrowArchitecture.allowedConcreteTypePatterns = ["^scala/Int#$"]
 */
package golden.architecture.conversion

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

trait NotArrowConvertShape[P[_, _], Q[_, _]] {
  def apply[A, B, C](p: P[A, B], extra: C): Q[A, B] // assert: RequireArrowArchitecture
}

final case class Bridge[P[_, _]: Arrow, Q[_, _]: Arrow](
    step: P[Int, Int]
)(implicit ev: ArrowConvert[P, Q]) {
  def bridged: Q[Int, Int] =
    ev.apply(step)
}

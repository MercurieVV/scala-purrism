/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.budgetovermax.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.budgetedTypeclasses = ["vocabulary.budgetovermax.ArrowConvert"]
RequireArrowArchitecture.profiles.default.maxInstantiations = 1
 */
package vocabulary.budgetovermax

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class TooManyConversions[P[_, _], Q[_, _], R[_, _]]( // assert: RequireArrowArchitecture.conversionBudget
  p: P[Int, Int]
)(implicit
  pq: ArrowConvert[P, Q],
  qr: ArrowConvert[Q, R]
)

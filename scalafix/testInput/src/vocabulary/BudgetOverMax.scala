/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.budgetovermax.*"]
RequireArrowArchitecture.budgetedTypeclasses = ["vocabulary.budgetovermax.ArrowConvert"]
RequireArrowArchitecture.maxInstantiations = 1
 */
package vocabulary.budgetovermax

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class TooManyConversions[P[_, _], Q[_, _], R[_, _]]( // assert: RequireArrowArchitecture
  p: P[Int, Int]
)(implicit
  pq: ArrowConvert[P, Q],
  qr: ArrowConvert[Q, R]
)

/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.budgetatmax.*"]
RequireArrowArchitecture.classes = ["scala\\.Int"]
RequireArrowArchitecture.budgetedTypeclasses = ["vocabulary.budgetatmax.ArrowConvert"]
RequireArrowArchitecture.maxInstantiations = 1
 */
package vocabulary.budgetatmax

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class Bridge[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
  pq: ArrowConvert[P, Q]
)

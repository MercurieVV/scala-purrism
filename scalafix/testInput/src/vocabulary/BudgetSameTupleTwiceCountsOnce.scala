/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.budgetsametwice.*"]
RequireArrowArchitecture.budgetedTypeclasses = ["vocabulary.budgetsametwice.ArrowConvert"]
RequireArrowArchitecture.maxInstantiations = 1
 */
package vocabulary.budgetsametwice

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class Bridge[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
  pq: ArrowConvert[P, Q]
)

object Bridge {
  def make[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
    pq2: ArrowConvert[P, Q]
  ): Bridge[P, Q] = Bridge(p)
}

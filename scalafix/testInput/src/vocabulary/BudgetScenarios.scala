/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.budgetscenarios.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["scala\\.Int"]
RestrictVocabulary.profiles.default.budgetedTypeclasses = ["vocabulary.budgetscenarios.ArrowConvert"]
RestrictVocabulary.profiles.default.maxInstantiations = 1
 */
package vocabulary.budgetscenarios

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

// at the max: one distinct ArrowConvert requirement, no overage
final case class OneConversion[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
  pq: ArrowConvert[P, Q]
)

// the same (P, Q) pair required twice, once in the class and once in its
// companion, counts once toward the budget -- not twice
final case class SharedBudgetAcrossCompanion[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
  pq: ArrowConvert[P, Q]
)

object SharedBudgetAcrossCompanion {
  def make[P[_, _], Q[_, _]](p: P[Int, Int])(implicit
    pq2: ArrowConvert[P, Q]
  ): SharedBudgetAcrossCompanion[P, Q] = SharedBudgetAcrossCompanion(p)
}

// two distinct instantiations exceeds the default max of 1
final case class TooManyConversions[P[_, _], Q[_, _], R[_, _]]( // assert: RestrictVocabulary.conversionBudget
  p: P[Int, Int]
)(implicit
  pq: ArrowConvert[P, Q],
  qr: ArrowConvert[Q, R]
)

/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.budget.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Int"]
RestrictVocabulary.profiles.default.budgetedTypeclasses = ["golden.architecture.budget.ArrowConvert"]
RestrictVocabulary.profiles.default.maxInstantiations = 1
 */
package golden.architecture.budget

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class OneConversion[P[_, _]: Arrow, Q[_, _]: Arrow](
  step: P[Int, Int]
)(implicit ev: ArrowConvert[P, Q])

final case class TwoConversions[P[_, _]: Arrow, Q[_, _]: Arrow, R[_, _]: Arrow]( // assert: RestrictVocabulary.conversionBudget
  step: P[Int, Int]
)(implicit ev1: ArrowConvert[P, Q], ev2: ArrowConvert[Q, R])

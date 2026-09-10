/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.nearmiss.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*"]
 */
package vocabulary.nearmiss

final case class Holder(
  step: Int => Int // assert: RequireArrowArchitecture
)

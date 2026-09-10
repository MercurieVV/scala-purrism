/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.nearmiss.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["cats\\.arrow\\..*"]
 */
package vocabulary.nearmiss

final case class Holder(
  step: Int => Int // assert: RequireArrowArchitecture.typeWhitelist
)

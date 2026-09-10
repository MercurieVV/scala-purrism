/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.outofwhitelist.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default = {}
 */
package vocabulary.outofwhitelist

final case class Holder(
  step: cats.data.Kleisli[cats.effect.IO, Int, Int] // assert: RequireArrowArchitecture.typeWhitelist
)

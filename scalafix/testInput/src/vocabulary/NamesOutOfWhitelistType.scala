/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.outofwhitelist.*"]
 */
package vocabulary.outofwhitelist

final case class Holder(
  step: cats.data.Kleisli[cats.effect.IO, Int, Int] // assert: RequireArrowArchitecture.typeWhitelist
)

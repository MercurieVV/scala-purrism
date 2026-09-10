/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.typewhitelistviolations.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*"]
 */
package vocabulary.typewhitelistviolations

final case class NamesFunction1(
  step: Int => Int // assert: RestrictVocabulary.typeWhitelist
)

final case class NamesKleisli(
  step: cats.data.Kleisli[cats.effect.IO, Int, Int] // assert: RestrictVocabulary.typeWhitelist
)

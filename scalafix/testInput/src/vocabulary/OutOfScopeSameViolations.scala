/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.inscopeonly.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.notinscope

final case class Holder(
  step: cats.data.Kleisli[cats.effect.IO, Int, Int]
) {
  def run(x: Int): Int =
    if (x > 0) x else -x
}

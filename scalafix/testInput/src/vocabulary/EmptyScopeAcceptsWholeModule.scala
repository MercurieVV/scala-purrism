/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = []
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["scala\\.Int"]
RestrictVocabulary.profiles.default.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.emptyscope

final case class Holder(x: Int) {
  def run: Int =
    if (x > 0) x else -x // assert: RestrictVocabulary.bannedConstruct
}

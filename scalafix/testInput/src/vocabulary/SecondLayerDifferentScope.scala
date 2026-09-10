/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.otherlayer.*"]
RestrictVocabulary.profile = "otherLayer"
RestrictVocabulary.profiles.otherLayer.classes = ["scala\\.Int", "scala\\.Predef\\.String"]
RestrictVocabulary.profiles.otherLayer.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package vocabulary.otherlayer

final case class Config(name: String, retries: Int)

final case class BadConfig(name: String) {
  var mutableName: String = name // assert: RestrictVocabulary.bannedConstruct
}

final case class NamesForeignType(
  other: cats.effect.IO[Int] // assert: RestrictVocabulary.typeWhitelist
)

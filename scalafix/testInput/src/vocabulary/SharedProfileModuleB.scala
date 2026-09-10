/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.sharedprofile\\.moduleb.*"]
RestrictVocabulary.profile = "shared"
RestrictVocabulary.profiles.shared.classes = ["scala\\.Int"]
RestrictVocabulary.profiles.shared.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.sharedprofile.moduleb

// Same profile name and body as SharedProfileModuleA.scala, a different
// scope -- proves the same named profile enforces identically wherever a
// module opts into it, not just within one scope.
final case class Holder(x: Int) {
  def run: Int =
    if (x > 0) x else -x // assert: RestrictVocabulary.bannedConstruct
}

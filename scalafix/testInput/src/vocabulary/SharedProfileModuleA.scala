/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.sharedprofile\\.modulea.*"]
RestrictVocabulary.profile = "shared"
RestrictVocabulary.profiles.shared.classes = ["scala\\.Int"]
RestrictVocabulary.profiles.shared.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.sharedprofile.modulea

// A separate "module" (its own scope) can reference the exact same profile
// name and body as SharedProfileModuleB.scala -- profiles are just data a
// module opts into, not tied to any one scope.
final case class Holder(x: Int) {
  def run: Int =
    if (x > 0) x else -x // assert: RestrictVocabulary.bannedConstruct
}

/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.bannedconstructs.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["scala\\.Int"]
RestrictVocabulary.profiles.default.bannedConstructs = ["scala.meta.Term.If", "scala.meta.Term.Match", "scala.meta.Defn.Var", "scala.meta.Term.For", "scala.meta.Term.While", "scala.meta.Term.Try"]
 */
package vocabulary.bannedconstructs

final case class Holder(x: Int) {
  def usesIf: Int =
    if (x > 0) x else -x // assert: RestrictVocabulary.bannedConstruct

  def usesMatch: Int =
    x match { // assert: RestrictVocabulary.bannedConstruct
      case 0 => 0
      case n => n
    }

  var cache: Int = 0 // assert: RestrictVocabulary.bannedConstruct

  def usesFor =
    for (i <- 1 to x) println(i) // assert: RestrictVocabulary.bannedConstruct

  def usesWhile =
    while (cache < x) { cache = cache + 1 } // assert: RestrictVocabulary.bannedConstruct

  def usesTry =
    try x / 0 // assert: RestrictVocabulary.bannedConstruct
    catch { case _ => 0 }
}

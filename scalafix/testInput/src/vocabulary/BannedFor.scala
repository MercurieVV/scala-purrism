/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedfor.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.bannedConstructs = ["scala.meta.Term.For"]
 */
package vocabulary.bannedfor

final case class Holder(x: Int) {
  def run =
    for (i <- 1 to x) println(i) // assert: RequireArrowArchitecture.bannedConstruct
}

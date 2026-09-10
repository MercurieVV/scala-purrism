/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedwhile.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.bannedConstructs = ["scala.meta.Term.While"]
 */
package vocabulary.bannedwhile

final case class Holder(x: Int) {
  def run = {
    var i: Int = 0
    while (i < x) { i = i + 1 } // assert: RequireArrowArchitecture.bannedConstruct
  }
}

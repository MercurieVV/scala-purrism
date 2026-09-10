/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedmatch.*"]
RequireArrowArchitecture.classes = ["scala\\.Int"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Term.Match"]
 */
package vocabulary.bannedmatch

final case class Holder(x: Int) {
  def run: Int =
    x match { // assert: RequireArrowArchitecture.bannedConstruct
      case 0 => 0
      case n => n
    }
}

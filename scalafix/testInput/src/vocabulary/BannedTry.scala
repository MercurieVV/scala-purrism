/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedtry.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.bannedConstructs = ["scala.meta.Term.Try"]
 */
package vocabulary.bannedtry

final case class Holder(x: Int) {
  def run =
    try x / 0 // assert: RequireArrowArchitecture.bannedConstruct
    catch { case _ => 0 }
}

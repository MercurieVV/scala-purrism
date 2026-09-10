/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = []
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.emptyscope

final case class Holder(x: Int) {
  def run: Int =
    if (x > 0) x else -x // assert: RequireArrowArchitecture.bannedConstruct
}

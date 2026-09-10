/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedvar.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["scala\\.Int"]
RequireArrowArchitecture.profiles.default.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package vocabulary.bannedvar

final case class Holder(x: Int) {
  var cache: Int = 0 // assert: RequireArrowArchitecture.bannedConstruct
}

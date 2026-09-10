/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedvar.*"]
RequireArrowArchitecture.classes = ["scala\\.Int"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package vocabulary.bannedvar

final case class Holder(x: Int) {
  var cache: Int = 0 // assert: RequireArrowArchitecture
}

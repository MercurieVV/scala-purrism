/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.otherlayer.*"]
RequireArrowArchitecture.classes = ["scala\\.Int", "scala\\.Predef\\.String"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package vocabulary.otherlayer

final case class Config(name: String, retries: Int)

final case class BadConfig(name: String) {
  var mutableName: String = name // assert: RequireArrowArchitecture
}

final case class NamesForeignType(
  other: cats.effect.IO[Int] // assert: RequireArrowArchitecture
)

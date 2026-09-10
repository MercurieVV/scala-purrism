/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.bannedif.*"]
RequireArrowArchitecture.classes = ["scala\\.Int"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.bannedif

final case class Holder(x: Int) {
  def run: Int =
    if (x > 0) x else -x // assert: RequireArrowArchitecture.bannedConstruct
}

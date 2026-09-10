/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.caseclass.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*", "scala\\.Unit"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Defn.Var"]
 */
package golden.architecture.caseclass

import cats.arrow.Arrow

final case class Conforming[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]]
)

final case class PlainDataParam[Step[_, _]: Arrow](
  retries: Int, // assert: RequireArrowArchitecture.typeWhitelist
  step: Step[Int, Int]
)

final case class MutableField[Step[_, _]: Arrow](step: Step[Int, Int]) {
  var cache: Unit = () // assert: RequireArrowArchitecture.bannedConstruct
}

/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["golden\\.architecture\\.composition.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Function1", "scala\\.Int", "scala\\.package\\.Either", "scala\\.Predef\\.String"]
RestrictVocabulary.profiles.default.bannedConstructs = ["scala.meta.Term.If", "scala.meta.Term.Function"]
 */
package golden.architecture.composition

import cats.arrow.Arrow
import cats.syntax.all.*

final case class Wiring[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]],
  handleStep: Step[Int, Int]
) {
  def combined: Step[Int, Int] = {
    val normalized = handleStep.andThen(handleStep)
    normalized.andThen(handleStep)
  }

  def broken: Step[Int, Int] =
    if (true) handleStep else handleStep // assert: RestrictVocabulary.bannedConstruct

  def lambdaLogic: Int => Int =
    x => x + 1 // assert: RestrictVocabulary.bannedConstruct
}

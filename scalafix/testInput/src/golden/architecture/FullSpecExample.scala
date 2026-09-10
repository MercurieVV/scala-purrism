/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.fullexample.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Int", "scala\\.package\\.Either", "scala\\.Predef\\.String"]
 */
package golden.architecture.fullexample

import cats.arrow.Arrow
import cats.syntax.all.*

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

trait Pipeline[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
  def handle: Step[Int, Int]
}

final case class LivePipeline[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]],
  handleStep: Step[Int, Int]
) extends Pipeline[Step] {
  type Error = String
  def validate: Step[Int, Either[Error, Int]] = validateStep
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  def make[Step[_, _]: Arrow](
    normalize: Step[Int, Int],
    validateStep: Step[Int, Either[String, Int]],
    handleStep: Step[Int, Int]
  ): LivePipeline[Step] = {
    val normalized = normalize.andThen(validateStep)
    LivePipeline(handleStep = handleStep, validateStep = normalized)
  }
}

object PipelineWiring {
  def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
    p.handle
}

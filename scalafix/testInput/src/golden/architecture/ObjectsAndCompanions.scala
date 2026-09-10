/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.objects.**"]
RequireArrowArchitecture.allowedConcreteTypePatterns = ["^scala/Int#$"]
 */
package golden.architecture.objects

import cats.arrow.Arrow
import cats.syntax.all.*

trait Pipeline[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

final case class LivePipeline[Step[_, _]: Arrow](handleStep: Step[Int, Int]) extends Pipeline[Step] {
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  def make[Step[_, _]: Arrow](handleStep: Step[Int, Int]): LivePipeline[Step] =
    LivePipeline(handleStep)
}

object PlainWiring {
  def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
    p.handle

  def evidenceInPlainObject[Step[_, _]: Arrow]( // assert: RequireArrowArchitecture
      s: Step[Int, Int]
  ): Step[Int, Int] =
    s
}

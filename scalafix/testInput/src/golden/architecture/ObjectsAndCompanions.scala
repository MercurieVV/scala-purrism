/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.objects.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*"]
 */
package golden.architecture.objects

import cats.arrow.Arrow
import cats.syntax.all.*

trait Pipeline[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

final case class LivePipeline[Step[_, _]: Arrow](handleStep: Step[Int, Int])
    extends Pipeline[Step] {
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  def make[Step[_, _]: Arrow](handleStep: Step[Int, Int]): LivePipeline[Step] =
    LivePipeline(handleStep)
}

// The old engine reserved a generic method carrying an evidence bound
// (: Arrow here) for companion-object constructors -- the same shape in a
// plain object was a violation. The new engine dropped that carve-out
// entirely: a plain object's generic def is unrestricted the same way a
// companion's is, as long as it never names a disallowed concrete type.
object PlainWiring {
  def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
    p.handle

  def evidenceInPlainObject[Step[_, _]: Arrow](
    s: Step[Int, Int]
  ): Step[Int, Int] =
    s

  // what's still caught: naming a disallowed concrete type, companion or not
  val label: String = "wiring" // assert: RequireArrowArchitecture
}

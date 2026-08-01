/*
rules = [PreferArrow]

# Body-only, arity-3 fan-out. The enclosing def is already Kleisli-typed via
# the `-->` alias, so only the interior of the `Kleisli { task => ... }` is
# rewritten; the signature is untouched. Mirrors `changedPlan`
# (gh-tasks-llm-executor/main.scala:886-900), the arity-3 case #4's original
# matcher sketch put out of scope.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyFanOut3 {
  type -->[F[_], A, B] = Kleisli[F, A, B]

  final class Checks[F[_]: Monad](
      filesChanged: Kleisli[F, String, Boolean],
      hasCommits: Kleisli[F, String, Boolean],
      hasPr: Kleisli[F, String, Boolean]
  ) {
    def changedPlan: -->[F, String, (Boolean, Boolean, Boolean)] =
      Kleisli { task =>
        for {
          a <- filesChanged.run(task)
          b <- hasCommits.run(task)
          c <- hasPr.run(task)
        } yield (a, b, c)
      }
  }
}

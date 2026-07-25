/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The same `ask <* work` shape, but the work reads a *projection* of the input
# -- so the right operand carries a `.local`. Structurally cheap (two plumbing
# nodes, exactly at the conservative ceiling) but textually expensive: `.local`
# must annotate its parameter with the input type or the new input type infers
# as `Any`. The conservative length-ratio guard therefore declines it, exactly
# as it declines `ArrowBodyLocalProjection`. That guard is the backstop against
# pathological blow-up and is left alone, so this sub-shape needs the flag.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._

object ArrowBodyAskDiscardProjected {
  final case class Task(path: String)

  final class Runner[F[_]: Monad] {
    val validatePath: Kleisli[F, String, Unit] =
      Kleisli { path => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli { task =>
        validatePath.run(task.path).as(task)
      }
  }
}

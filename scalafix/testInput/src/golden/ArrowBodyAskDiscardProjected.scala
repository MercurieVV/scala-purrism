/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The same `ask <* work` shape, but the work reads a *projection* of the input
# -- so the right operand carries a `.local`. With `aggressive` set, the
# length-ratio guard accepts the point-free form.
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

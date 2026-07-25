/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The same `ask <* work` shape, but the work reads a *projection* of the input
# -- so the right operand would carry a `.local`. Case 1 recognition is not
# implemented yet, so with `aggressive` set this falls to the existing
# input-capturing fan-out (`&&&`) branch instead. Pinned as the executed
# oracle until case 1 lands.
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

/*
rules = [PreferArrow]

# `Kleisli { task => validate.run(task).as(task) }`: the effect runs on the
# arrow's own input and its result is thrown away in favour of that input.
# That is `Kleisli.ask <* work` -- `ask` supplies the input as the value,
# `<*` runs the work for its effect only. `<*` needs just `Apply[F]`, so this
# costs less than the `ask &&& work` fan-out plus destructuring `map` the rule
# would otherwise need to carry the input past the discarded result.
#
# Fires without `PreferArrow.aggressive`: one plumbing node (the `ask`) and a
# rendering shorter than the length guard's ceiling.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._

object ArrowBodyAskDiscard {
  final case class Task(path: String)

  final class Runner[F[_]: Monad] {
    val validate: Kleisli[F, Task, Unit] =
      Kleisli { task => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli { task =>
        validate.run(task).as(task)
      }
  }
}

/*
rules = [PreferArrow]

# `Kleisli { task => task.pure[F] }` is the identity arrow written out by hand:
# `Kleisli.ask` is defined as `Kleisli(F.pure)`. Recognised by the symbol of
# the `pure` -- `cats/syntax/ApplicativeIdOps#pure().` -- applied to a name
# that resolves to the arrow's own input, not by the spelling `pure`.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.applicative._

object ArrowBodyAskPure {
  final case class Task(path: String)

  final class Runner[F[_]: Monad] {
    def echo: Kleisli[F, Task, Task] =
      Kleisli { task =>
        task.pure[F]
      }

    val echoValue: Kleisli[F, Task, Task] =
      Kleisli { task => task.pure }
  }
}

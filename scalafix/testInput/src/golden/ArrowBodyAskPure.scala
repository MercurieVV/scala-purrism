/*
rules = [PreferArrow]

# `Kleisli { task => task.pure[F] }` is the identity arrow written out by hand:
# `Kleisli.ask` is defined as `Kleisli(F.pure)`. Recognised by the symbol of
# the `pure` -- `cats/syntax/ApplicativeIdOps#pure().` -- applied to a name
# that resolves to the arrow's own input, never by the spelling `pure`.
#
# The typed `Kleisli.ask[F, Task]` is emitted rather than a bare
# `Kleisli.ask`: at a value position there is nothing for `ask`'s two type
# parameters to be inferred from. This is the one shape whose whole tree is a
# single node, which is why the budget's effect-count floor and length ratio
# are both waived for a bare `Ask` and for nothing else.
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

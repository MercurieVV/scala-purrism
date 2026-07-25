
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

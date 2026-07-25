
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

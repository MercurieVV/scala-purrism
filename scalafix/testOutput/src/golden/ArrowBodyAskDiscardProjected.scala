
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._
import cats.syntax.apply._

object ArrowBodyAskDiscardProjected {
  final case class Task(path: String)

  final class Runner[F[_]: Monad] {
    val validatePath: Kleisli[F, String, Unit] =
      Kleisli { path => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli.ask[F, Task] <* validatePath.local((task: Task) => task.path)
  }
}


package golden

import cats.effect.Sync
import cats.syntax.functor._
import cats.data.Kleisli

object KleisliLiftCallbackParam {
  final case class Root(path: String)
  final case class Task(id: Int)

  def dependencyConclusion[F[_]: Sync](progress: String => F[Unit]): Kleisli[F, (Root, Task), Option[String]] =
  Kleisli.apply { case (root, task) =>
    progress(s"checking ${task.id}").as(Some(root.path))
  }

  def caller[F[_]: Sync](log: String => F[Unit]): F[Option[String]] =
    dependencyConclusion(log)((Root("/tmp"), Task(1)))
}


package golden

import cats.effect.Sync
import cats.data.Kleisli

object KleisliLiftPolyDef {
  final case class Root(path: String)
  final case class Task(id: Int)

  def dependencyConclusion[F[_]: Sync]: Kleisli[F, (Root, Task), Option[String]] =
  Kleisli.apply { case (root, task) =>
    Sync[F].pure(Some(s"${root.path}/${task.id}"))
  }
}

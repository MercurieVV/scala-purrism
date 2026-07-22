/*
rules = [PreferKleisli]

# A method-level `[F[_]: Sync]` def whose parameters mix data with an effect
# callback (`progress: String => F[Unit]`). The data tuples into the Kleisli's
# input; the callback stays a leading parameter list, so call sites partially
# apply it rather than threading a logger through `.local`.
 */
package golden

import cats.effect.Sync
import cats.syntax.functor._

object KleisliLiftCallbackParam {
  final case class Root(path: String)
  final case class Task(id: Int)

  def dependencyConclusion[F[_]: Sync](
      root: Root,
      task: Task,
      progress: String => F[Unit]
  ): F[Option[String]] =
    progress(s"checking ${task.id}").as(Some(root.path))

  def caller[F[_]: Sync](log: String => F[Unit]): F[Option[String]] =
    dependencyConclusion(Root("/tmp"), Task(1), log)
}

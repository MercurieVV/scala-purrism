/*
rules = [PreferKleisli]

# Probe: a method-level `[F[_]: Sync]` def with several plain parameters --
# the shape every def in the reference corpus has. No output file: this
# records that `PreferKleisli` currently declines it.
 */
package golden

import cats.effect.Sync

object KleisliLiftPolyDef {
  final case class Root(path: String)
  final case class Task(id: Int)

  def dependencyConclusion[F[_]: Sync](
      root: Root,
      task: Task
  ): F[Option[String]] =
    Sync[F].pure(Some(s"${root.path}/${task.id}"))
}

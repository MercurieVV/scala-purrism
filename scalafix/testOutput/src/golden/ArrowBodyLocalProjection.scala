
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyLocalProjection {
  final class Reporter[F[_]: Monad] {
    val summarise: Kleisli[F, (Int, String), Unit] =
      Kleisli { case (id, label) => Monad[F].unit }

    def report: Kleisli[F, (Int, String, Boolean), String] =
      summarise.local((entry: (Int, String, Boolean)) => (entry._1, entry._2)).as("done")
  }

  def summariseWith[G[_]: Monad](tag: String): Kleisli[G, (Int, String), Unit] =
    Kleisli { case (id, label) => Monad[G].unit }

  // The callee is a *curried application*, not a name -- which is exactly what
  // `PreferKleisli` leaves behind when it lifts a def that has a callback
  // parameter (`comment(progress)((root, task))`). An application node carries
  // no symbol, so asking it for its type answers nothing; the head has to be
  // resolved and its supplied parameter lists counted instead.
  final class CurriedReporter[F[_]: Monad] {
    def report: Kleisli[F, (Int, String, Boolean), String] =
      summariseWith[F]("tag").local((entry: (Int, String, Boolean)) => (entry._1, entry._2)).as("done")
  }

  // The tail reshape closes over the arrow input -- `entry._2` appears in the
  // constant the `.as` produces. Point-free has no name for the input there,
  // so the conservative budget declines. Aggressive mode carries it along with
  // a leading `Kleisli.ask` and destructures it back out, which is the
  // plumbing-for-coverage trade the flag exists to make.
  final class CapturingReporter[F[_]: Monad] {
    def report: Kleisli[F, (Int, String, Boolean), String] =
      (Kleisli.ask[F, (Int, String, Boolean)] &&& summariseWith[F]("tag").local((entry: (Int, String, Boolean)) => (entry._1, entry._2))).map({
  case (entry, _) =>
    s"done: ${
      entry._2
    }"
})
  }
}

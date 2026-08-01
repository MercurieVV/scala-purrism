
package crossfile

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._

object ArrowCrossFileCaller {
  def report[F[_]: Monad]: Kleisli[F, (Int, String, Boolean), String] =
    ArrowCrossFileStore.summarise[F]("tag").local((entry: (Int, String, Boolean)) => (entry._1, entry._2)).as("done")
}

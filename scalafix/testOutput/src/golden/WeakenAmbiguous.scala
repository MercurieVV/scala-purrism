package golden

import cats.effect.Async
import cats.syntax.all.*

object WeakenAmbiguous {
  def timed[F[_]: Async as A](fa: F[Int]): F[Int] = 
    A.uncancelable(_ => A.monotonic.flatMap(_ => fa))
}

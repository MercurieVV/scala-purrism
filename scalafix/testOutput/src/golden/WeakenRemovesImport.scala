package golden

import cats.syntax.all.*
import cats.Functor

object WeakenRemovesImport {
  def twice[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ * 2)
}

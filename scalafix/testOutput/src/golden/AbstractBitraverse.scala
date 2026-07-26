package golden

import cats.Bitraverse
import cats.Applicative

object AbstractBitraverse {
  def traverse[F[_]: Applicative, E, A, B, C](
      either: Either[E, A],
      f: E => F[B],
      g: A => F[C]
  ): F[Either[B, C]] = {
    val bitraverse = implicitly[Bitraverse[Either]]
    bitraverse.bitraverse(either)(f, g)
  }
}

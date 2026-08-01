package golden

import cats.Applicative
import cats.Traverse
import cats.syntax.all._

final class BlockValUsedTwice[F[_]: Traverse, G[_]: Applicative] {
  def test(
      fga: F[G[Int]],
      combine: (G[F[Int]], G[F[Int]]) => G[F[Int]]
  ): G[F[Int]] = {
    val sequenced = fga.sequence
    combine(sequenced, sequenced)
  }
}

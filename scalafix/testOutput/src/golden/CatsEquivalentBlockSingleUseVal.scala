package golden

import cats.Applicative
import cats.Traverse
import cats.syntax.all._

final class BlockSingleUseVal[F[_]: Traverse, G[_]: Applicative] {
  def test(fga: F[G[Int]]): G[F[Int]] = fga.sequence
}

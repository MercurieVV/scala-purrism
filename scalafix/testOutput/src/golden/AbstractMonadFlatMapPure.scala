
package golden

import cats.Monad
import cats.syntax.applicative._
import cats.syntax.flatMap._

object AbstractMonadFlatMapPure {
  private def mkList[G[_]: Monad](x: Int): G[Int] =
    x.pure[G].flatMap(a => (a + 1).pure[G]).flatMap(b => b.pure[G])
}

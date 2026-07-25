
package golden

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.functor._

object AbstractApplicativePure {
  private def wrap[G[_]: Applicative](x: Int): G[Int] = x.pure[G].map(_ + 1)
}


package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.compose._

final class BodyChainFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String],
    summarise: Kleisli[F, String, Int]
) {
  def enrich: Kleisli[F, String, Int] =
    loadUser >>> loadOrders >>> summarise
}

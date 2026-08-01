
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.compose._

object ArrowSignaturePolyDef {
  def loadUser[F[_]: Monad]: Kleisli[F, String, String] =
    Kleisli(Monad[F].pure)

  def loadOrders[F[_]: Monad]: Kleisli[F, String, String] =
    Kleisli(Monad[F].pure)

  def enrich[F[_]: Monad]: Kleisli[F, String, String] =
  loadUser[F] >>> loadOrders[F]
}

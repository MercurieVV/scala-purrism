
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyAggressiveConstArm {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def total: F[Int]
  }

  final class Totals[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Int)] =
      Kleisli { (ctx: (Int, Int)) => service.size(ctx._1) } &&& Kleisli { (ctx: (Int, Int)) => service.total }
  }
}

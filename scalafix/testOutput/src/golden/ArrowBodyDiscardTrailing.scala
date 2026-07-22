
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyDiscardTrailing {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def active(branch: Int): F[Boolean]
    def record(size: Int): F[Unit]
  }

  final class Checks[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Boolean)] =
      (Kleisli { (ctx: (Int, Int)) => service.size(ctx._1) } &&& Kleisli { (ctx: (Int, Int)) => service.active(ctx._2) }).flatTap { case (size, active) => Kleisli.liftF(service.record(size)) }
  }
}

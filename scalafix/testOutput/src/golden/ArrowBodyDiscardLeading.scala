
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._

object ArrowBodyDiscardLeading {
  trait Service[F[_]] {
    def announce(label: Int): F[Unit]
    def size(path: Int): F[Int]
  }

  final class Checks[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), Int] =
      Kleisli { (ctx: (Int, Int)) => service.announce(ctx._2) } *> Kleisli { (ctx: (Int, Int)) => service.size(ctx._1) }
  }
}

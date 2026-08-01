/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# A discard generator *after* the work, reading what the work produced, is a
# `flatTap`: it runs the effect and keeps the value. The two named generators
# still fan out with `&&&`, and the tap destructures their tuple so the source
# `for`'s own binding names survive into the tap body.
#
# The tap is lifted with `Kleisli.liftF` rather than a typed lambda because
# inside `flatTap` the expected type is already fixed by the receiver, so
# there is nothing left to infer. A trailing discard that read the *arrow
# input* would be declined instead -- the input is out of scope there.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyDiscardTrailing {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def active(branch: Int): F[Boolean]
    def record(size: Int): F[Unit]
  }

  final class Checks[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Boolean)] =
      Kleisli { ctx =>
        for {
          size   <- service.size(ctx._1)
          active <- service.active(ctx._2)
          _      <- service.record(size)
        } yield (size, active)
      }
  }
}

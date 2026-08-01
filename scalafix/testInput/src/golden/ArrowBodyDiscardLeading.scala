/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# A discard generator ahead of the work -- `_ <- announce(...)` -- is the
# shape `*>` exists for. Both sides are fed the same input, the left result is
# dropped, and the order the `for` specified is preserved exactly. `*>` needs
# only `Apply[F]`, weaker than the `Monad[F]` an `&&&` fan-out costs, and it
# needs no `ask` to carry the kept value past the discarded one.
#
# Note this fires with a *single* named generator: a discard counts towards the
# "at least two effects" floor, because eliminating the `for` around one
# effectful call plus one logged side effect is a real gain.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyDiscardLeading {
  trait Service[F[_]] {
    def announce(label: Int): F[Unit]
    def size(path: Int): F[Int]
  }

  final class Checks[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), Int] =
      Kleisli { ctx =>
        for {
          _    <- service.announce(ctx._2)
          size <- service.size(ctx._1)
        } yield size
      }
  }
}

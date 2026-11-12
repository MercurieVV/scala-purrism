/*
rules = [TypeclassWeakening]

# Bounds the rule must leave alone. `map` + `foldLeft` need Functor and
# Foldable: the only single typeclass covering both is Traverse itself, and
# two bounds are never emitted. An external call the index cannot explain
# keeps the bound. A def implementing an abstract member keeps its signature,
# and so does one that can be overridden (a non-final, non-private member of
# a trait or non-final class): its overriders may live in other files. A
# `final` or `private` member cannot be overridden and is weakened. A
# weakened bound's original import stays while another reference to it
# remains in the file.
 */
package golden

import cats.Traverse
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all.*

object WeakenKeep {
  def summed[F[_]: Traverse](fa: F[Int]): (F[Int], Int) =
    (fa.map(_ + 1), fa.foldLeft(0)(_ + _))

  def random[F[_]: Sync]: F[Int] =
    Random.scalaUtilRandom[F].flatMap(_.nextInt)

  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def suspended[F[_]: Sync](x: => Int): F[Int] =
    Sync[F].delay(x)

  trait Step {
    def run[F[_]: Sync](fa: F[Int]): F[Int]
  }

  object Doubling extends Step {
    def run[F[_]: Sync](fa: F[Int]): F[Int] =
      fa.map(_ * 2)
  }

  trait Base {
    def run[F[_]: Sync](fa: F[Int]): F[Int] =
      fa.map(_ + 1)

    final def twice[F[_]: Sync](fa: F[Int]): F[Int] =
      fa.map(_ * 2)
  }

  object Impl extends Base {
    override def run[F[_]: Sync](fa: F[Int]): F[Int] =
      fa.map(_ + 2)
  }

  class Holder {
    private def inc[F[_]: Sync](fa: F[Int]): F[Int] =
      fa.map(_ + 1)

    final def use[F[_]: Sync](fa: F[Int]): F[Int] =
      inc(fa)
  }
}

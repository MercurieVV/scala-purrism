package golden

import cats.Traverse
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all.*
import cats.Functor

object WeakenKeep {
  def summed[F[_]: Traverse](fa: F[Int]): (F[Int], Int) =
    (fa.map(_ + 1), fa.foldLeft(0)(_ + _))

  def random[F[_]: Sync]: F[Int] =
    Random.scalaUtilRandom[F].flatMap(_.nextInt)

  def bump[F[_]: Functor](fa: F[Int]): F[Int] =
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
}

/*
rules = [TypeclassWeakening]

# Bounds that are already the weakest covering typeclass stay as written:
# running the rule over its own output changes nothing.
 */
package golden

import cats.{Applicative, ApplicativeThrow, Apply, FlatMap, Functor, Monad, MonadThrow}
import cats.syntax.all.*

object WeakenIdempotent {
  def bump[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def paired[F[_]: Apply](fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.product(fb).map(identity)

  def lifted[F[_]: Applicative]: F[Int] =
    1.pure[F].map(_ + 1)

  def chain[F[_]: FlatMap](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def chainPure[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.flatMap(a => (a + 1).pure[F])

  def safe[F[_]: ApplicativeThrow](fa: F[Int]): F[Int] =
    fa.handleError(_ => 0)

  def failing[F[_]: MonadThrow](fa: F[Int]): F[Int] =
    fa.flatMap(_ => (new RuntimeException("boom"): Throwable).raiseError[F, Int])
}

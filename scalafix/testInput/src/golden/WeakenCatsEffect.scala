/*
rules = [TypeclassWeakening]

# cats-effect typeclasses come from a TASTy scan of the classpath at rule
# runtime. Binary ancestors (ApplicativeError, MonadError, MonadCancel) are
# emitted through their Throwable aliases. A body that needs the bound's own
# operations (`delay`, `sleep`) keeps the bound; an alias bound such as
# `MonadThrow` weakens like any other. An original's import goes once nothing
# refers to it (`Async`), and stays while something does (`MonadThrow`, the
# target of `failing`).
 */
package golden

import cats.MonadThrow
import cats.effect.Async
import cats.effect.Sync
import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*

object WeakenCatsEffect {
  def safe[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.handleError(_ => 0)

  def failing[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.flatMap(_ => (new RuntimeException("boom"): Throwable).raiseError[F, Int])

  def guarded[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.uncancelable

  def suspended[F[_]: Sync](x: => Int): F[Int] =
    Sync[F].delay(x)

  def nap[F[_]: Temporal as T]: F[Unit] =
    T.sleep(1.second)

  def bumpThrow[F[_]: MonadThrow](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def bumpAsync[F[_]: Async](fa: F[Int]): F[Int] =
    fa.map(_ + 2)
}

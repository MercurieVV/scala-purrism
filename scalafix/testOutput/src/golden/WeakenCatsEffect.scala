package golden

import cats.MonadThrow
import cats.effect.Sync
import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import cats.{ApplicativeThrow, Functor}
import cats.effect.MonadCancelThrow

object WeakenCatsEffect {
  def safe[F[_]: ApplicativeThrow](fa: F[Int]): F[Int] =
    fa.handleError(_ => 0)

  def failing[F[_]: MonadThrow](fa: F[Int]): F[Int] =
    fa.flatMap(_ => (new RuntimeException("boom"): Throwable).raiseError[F, Int])

  def guarded[F[_]: MonadCancelThrow](fa: F[Int]): F[Int] =
    fa.uncancelable

  def suspended[F[_]: Sync](x: => Int): F[Int] =
    Sync[F].delay(x)

  def nap[F[_]: Temporal as T]: F[Unit] =
    T.sleep(1.second)

  def bumpThrow[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def bumpAsync[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 2)
}

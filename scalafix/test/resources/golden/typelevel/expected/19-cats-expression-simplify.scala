package golden.typelevel

import cats.syntax.all.*

final class CatsExpressionSimplify[F[_]] {
  def discard(seed: F[Int]): F[Unit] =
    seed.void

  def replace(seed: F[Int]): F[String] =
    seed.as("done")

  def mapped(seed: F[Int]): F[String] =
    seed.map(value => value.toString)

  def sequence(first: F[Unit], second: F[String]): F[String] =
    first *> second

  def combine(first: F[Int], second: F[String]): F[(Int, String)] =
    (first, second).mapN((value, label) => (value, label))

  def optional(value: String): Option[String] =
    Option(value)

  def either(valid: Boolean, value: String): Either[String, String] =
    Either.cond(valid, value, "invalid")
}

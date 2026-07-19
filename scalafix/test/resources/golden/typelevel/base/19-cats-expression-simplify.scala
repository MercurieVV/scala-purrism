package golden.typelevel

import cats.syntax.all.*

final class CatsExpressionSimplify[F[_]] {
  def discard(seed: F[Int]): F[Unit] =
    seed.map(_ => ())

  def replace(seed: F[Int]): F[String] =
    seed.map(_ => "done")

  def mapped(seed: F[Int]): F[String] =
    seed.flatMap(value => value.toString.pure[F])

  def sequence(first: F[Unit], second: F[String]): F[String] =
    first.flatMap(_ => second)

  def combine(first: F[Int], second: F[String]): F[(Int, String)] =
    first.flatMap(value => second.map(label => (value, label)))

  def optional(value: String): Option[String] =
    if value == null then None else Some(value)

  def either(valid: Boolean, value: String): Either[String, String] =
    if valid then Right(value) else Left("invalid")
}

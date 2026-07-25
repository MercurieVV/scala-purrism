/*
rules = [DisableSyntax]
 */
package hkt

import cats.Applicative
import cats.syntax.all.*

final class UsageAnalyzerCases {
  private implicit final class LocalOps(values: List[Int]) {
    def locallyUnindexed: List[Int] = values
  }

  private def mapOnly(values: List[Int]): List[Int] =
    values.map(identity)

  private def traverseOnly[F[_]: Applicative](values: List[Int])(f: Int => F[Int]): F[List[Int]] =
    values.traverse(f)

  private def foldMapOnly(values: List[Int]): Int =
    values.foldMap(identity)

  private def headOnly(values: List[Int]): Int =
    values.head

  private def nilMatch(values: List[Int]): Int =
    values match {
      case Nil => 0
      case _   => 1
    }

  private def missingCapability(values: List[Int]): List[Int] =
    values.locallyUnindexed

  private def ambiguousCapability(values: List[Int]): Int =
    values.reduce(_ + _)

  private def binary(value: Either[String, Int]): Either[String, Int] =
    value

  def publicMap(values: List[Int]): List[Int] =
    values.map(identity)

  def publicHead(values: List[Int]): Int =
    values.head

  private def unsafeBody(values: List[Int]): Int = {
    var acc = values.head
    if (values.isEmpty) throw new IllegalArgumentException("empty")
    acc
  }
}

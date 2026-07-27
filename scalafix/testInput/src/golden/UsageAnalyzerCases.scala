/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative
import cats.syntax.all.*

final class UsageAnalyzerCases {
  private def mapOnly(values: List[Int]): List[Int] =
    values.map(identity)

  private def traverseOnly[F[_]: Applicative](
      values: List[Int]
  )(f: Int => F[Int]): F[List[Int]] =
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
    values.reverse

  private def ambiguousCapability(values: List[Int]): Int =
    values.reduce((left, _) => left)

  private def indexed(values: List[Int], index: Int): Int =
    values(index)

  private def unsafeCast(values: List[Int]): List[Int] =
    values.asInstanceOf[List[Int]]

  private def binary(value: Either[String, Int]): Either[String, Int] =
    value

  private def typeLambda(
      value: ([X] =>> Either[String, X])[Int]
  ): ([X] =>> Either[String, X])[Int] =
    value

  protected def bareProtected(values: List[Int]): List[Int] =
    values

  private[golden] def packagePrivate(values: List[Int]): List[Int] =
    values

  def publicHead(values: List[Int]): Int =
    values.head

  def publicMap(values: List[Int]): List[Int] =
    values.map(identity)

  def localOwner(values: List[Int]): List[Int] = {
    def localDefinition(input: List[Int]): List[Int] =
      input.map(identity)

    localDefinition(values)
  }
}

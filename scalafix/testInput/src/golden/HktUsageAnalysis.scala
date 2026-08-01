/*
rules = [DisableSyntax]
 */
package golden

import cats.Applicative
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*

final class HktUsageAnalysis {
  private var stored: List[Int] = Nil

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

  private def consMatch(values: List[Int]): Int =
    values match {
      case _ :: _ => 1
      case Nil    => 0
    }

  private def someMatch(value: Option[Int]): Int =
    value match {
      case Some(number) => number
      case None         => 0
    }

  private def missingCapability(values: List[Int]): List[Int] =
    values.distinct

  private def ambiguousCapability(values: List[Int]): Int =
    values.reduce((left, _) => left)

  private def indexed(values: List[Int], index: Int): Int =
    values(index)

  private def unsafeCast(values: List[Int]): List[Int] =
    values.asInstanceOf[List[Int]]

  private def unsafeEffect(value: IO[Int])(using IORuntime): Int =
    value.unsafeRunSync()

  private def mutableVariable(values: List[Int]): List[Int] = {
    var current = values
    current = values
    current
  }

  private def mutableAssignment(values: List[Int]): List[Int] = {
    stored = values
    values
  }

  private def namedArgument(values: List[Int]): List[Int] = {
    def keep(input: List[Int]): List[Int] = input
    keep(input = values)
  }

  private def binary(value: Either[String, Int]): Either[String, Int] =
    value

  private def binaryUnsafe(
      value: Either[String, Int]
  ): Either[String, Int] =
    value.asInstanceOf[Either[String, Int]]

  private def typeLambda(
      value: ([X] =>> Either[String, X])[Int]
  ): ([X] =>> Either[String, X])[Int] =
    value

  protected def bareProtected(values: List[Int]): List[Int] =
    values

  private[golden] def packagePrivate(values: List[Int]): List[Int] =
    values

  def publicHead(values: List[Int]) =
    values.head

  def publicMap(values: List[Int]): List[Int] =
    values.map(identity)

  def localOwner(values: List[Int]): List[Int] = {
    def localDefinition(input: List[Int]): List[Int] =
      input.map(identity)

    localDefinition(values)
  }

  private def twoConstructors(values: List[Int], vs: Vector[Int]): List[Int] =
    values
}

private object RestrictedOwner {
  def restrictedOwner(values: List[Int]): List[Int] = values
}

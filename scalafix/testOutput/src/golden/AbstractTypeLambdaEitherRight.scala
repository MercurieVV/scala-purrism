package golden

import cats.Functor

object AbstractTypeLambdaEitherRight {
  def process[E, A, B](either: Either[E, A], f: A => B): Either[E, B] = {
    type ErrorOr[X] = Either[E, X]
    val functor = implicitly[Functor[ErrorOr]]
    functor.map(either)(f)
  }
}

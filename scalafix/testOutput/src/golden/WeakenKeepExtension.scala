package golden

import cats.effect.Sync
import cats.syntax.all.*
import cats.Applicative

object WeakenKeepExtension {
  trait Ops {
    extension (fa: List[Int])
      def bumpAll[F[_]: Sync](f: Int => F[Int]): F[List[Int]] =
        fa.traverse(f)
  }

  object FinalOps {
    extension (fa: List[Int])
      def bumpAll[F[_]: Applicative](f: Int => F[Int]): F[List[Int]] =
        fa.traverse(f)
  }
}

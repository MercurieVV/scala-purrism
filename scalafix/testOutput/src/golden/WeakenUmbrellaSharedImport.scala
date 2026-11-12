package golden

import cats.FlatMap
import cats.syntax.all.*
import cats.Functor

final class WeakenUmbrellaSharedImport[F[_]: Functor](fa: F[Int]) {
  def twice: F[Int] = fa.map(_ * 2)
}

object WeakenUmbrellaSharedImport {
  private def dup[S[_]: FlatMap](xs: S[Int]): S[Int] = xs.productR(xs)

  def run(xs: List[Int]): List[Int] = dup(xs)
}

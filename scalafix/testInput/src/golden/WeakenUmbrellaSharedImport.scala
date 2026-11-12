/*
rules = [TypelevelPurrism]

# `P` only maps, so its `FlatMap` bound weakens to `Functor`, the last mention
# of `FlatMap` in the original file. `PreferTypeParameters` meanwhile widens
# `dup` to `S[_]: FlatMap`, reusing the import already in scope rather than
# adding one, so the import must stay.
 */
package golden

import cats.FlatMap
import cats.syntax.all.*

final class WeakenUmbrellaSharedImport[F[_]: FlatMap](fa: F[Int]) {
  def twice: F[Int] = fa.map(_ * 2)
}

object WeakenUmbrellaSharedImport {
  private def dup(xs: List[Int]): List[Int] = xs.flatMap(_ => xs)

  def run(xs: List[Int]): List[Int] = dup(xs)
}

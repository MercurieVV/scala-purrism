package golden

import cats.Traverse
import cats.syntax.all.*

final class WeakenUmbrellaReusedParam[G[_]: Traverse](g: G[Int]) {
  def bumped: G[Int] = g.map(_ + 1)

  private def total(values: G[Int]): Int = values.foldLeft(0)(_ + _)
}

object WeakenUmbrellaReusedParam {
  def outer[H[_]: Traverse](h: H[Int]): H[Int] = {
    def summed(values: H[Int]): Int = values.foldLeft(0)(_ + _)
    h.map(_ + 1)
  }
}

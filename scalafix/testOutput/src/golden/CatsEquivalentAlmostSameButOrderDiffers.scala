package golden

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._

final class OrderDiffers[F[_]: Monad] {
  def test(fa: F[Int], fb: F[String]): F[(String, Int)] =
    fb.flatMap(b => fa.map(a => (b, a)))
}


package golden

import scala.util.Try
import cats.MonadError

private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int] = F.fromTry(Try(s.toInt))

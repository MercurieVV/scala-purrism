/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.MonadError
import scala.util.Try

private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int] = F.fromTry(Try(s.toInt))

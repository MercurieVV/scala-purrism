
package golden

import cats.syntax.all._
final class CatsSimplifyEitherCond {
  def validated(valid: Boolean, value: String): Either[String, String] =
    Either.cond(valid, value, "invalid")
}

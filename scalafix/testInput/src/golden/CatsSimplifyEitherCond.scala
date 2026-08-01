/*
rules = [SimplifyCatsExpressions]
 */
package golden

final class CatsSimplifyEitherCond {
  def validated(valid: Boolean, value: String): Either[String, String] =
    if (valid) Right(value) else Left("invalid")
}

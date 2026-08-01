/*
rules = [SimplifyCatsExpressions]

# `Right`, `Left` and `Some` are shadowed by local declarations, so neither
# `Either.cond` nor `Option(...)` describes what this code does. Matching on the
# spelling rewrites all three. This file must come back unchanged.
 */
package golden

final case class Right[A](value: A)
final case class Left[A](value: A)
final case class Some[A](value: A)

final class CatsNegativeShadowedRight {
  def validated(valid: Boolean, value: String): Any =
    if (valid) Right(value) else Left("invalid")

  def optional(value: String): Any =
    if (value == null) None else Some(value)
}

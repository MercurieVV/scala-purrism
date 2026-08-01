/*
rules = [SimplifyCatsExpressions]
 */
package golden

final class CatsSimplifyOption {
  def optional(value: String): Option[String] =
    if (value == null) None else Some(value)
}


package golden

import cats.syntax.all._
final class CatsSimplifyOption {
  def optional(value: String): Option[String] =
    Option(value)
}

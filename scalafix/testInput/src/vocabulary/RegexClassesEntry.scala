/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.regexclasses.*"]
RequireArrowArchitecture.classes = ["scala.Option", "cats\\.arrow\\..*", "scala\\.Int"]
 */
package vocabulary.regexclasses

import cats.arrow.Arrow

final case class Holder[Step[_, _]: Arrow](
  maybeStep: Option[Step[Int, Int]]
)

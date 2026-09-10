/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.selfref.*"]
 */
package vocabulary.selfref

trait Marker
final case class Holder(m: Marker)

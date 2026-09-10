/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.selfref.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default = {}
 */
package vocabulary.selfref

trait Marker
final case class Holder(m: Marker)

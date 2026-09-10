/*
rules = [RestrictVocabulary]

RestrictVocabulary.scope = ["vocabulary\\.typewhitelistconforming.*"]
RestrictVocabulary.profile = "default"
RestrictVocabulary.profiles.default.classes = ["scala.Option", "cats\\.arrow\\..*", "scala\\.Int"]
 */
package vocabulary.typewhitelistconforming

import cats.arrow.Arrow

// exact-FQCN and regex classes entries both work, and cover a nested type
// argument too (Step[Int, Int] inside Option[...])
final case class RegexAndExactEntries[Step[_, _]: Arrow](
  maybeStep: Option[Step[Int, Int]]
)

// a type declared in this same scoped package is implicitly whitelisted via
// scope, with no separate classes entry needed
trait Marker
final case class SelfReference(m: Marker)

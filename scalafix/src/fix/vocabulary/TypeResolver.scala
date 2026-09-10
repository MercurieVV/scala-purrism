package fix.vocabulary

import scala.meta.Type

/** How `RestrictVocabulary`'s checks get provenance for a named type --
  * resolved from a compiled payload when one exists
  * ([[SemanticIndexResolver]]), or approximated from the file's own imports
  * when it does not ([[ImportTableResolver]]).
  */
trait TypeResolver {

  /** The type's fully-qualified name (dotted, e.g. "cats.data.Kleisli"), or
    * `None` to skip the check for this occurrence rather than guess.
    */
  def resolve(tpe: Type): Option[String]

  /** True if this occurrence is a type parameter or abstract type member,
    * exempt from the whitelist regardless of the constructor it applies.
    */
  def isExempt(tpe: Type): Boolean
}

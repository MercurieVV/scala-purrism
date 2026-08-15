package fix

import scalafix.v1._

/** The three rules that rewrite an expression toward the Cats API, run
  * together. Each works a different tree shape, largest to smallest:
  *
  *   - `PreferCatsFunctions` matches a method's whole body against an index of
  *     known Cats source functions and rewrites the body to the call.
  *   - `PreferCatsSyntax` matches a summoner call -- `Typeclass[F].method(fa)`
  *     -- and rewrites it to dot syntax -- `fa.method(...)`.
  *   - `SimplifyCatsExpressions` matches dot-syntax sub-expressions already in
  *     that shape and collapses them to a tighter combinator, e.g.
  *     `fa.map(_ => ())` to `fa.void`.
  *
  * None takes configuration of its own, so this umbrella takes none either.
  */
final class PreferCatsExpressions
    extends SemanticRule("PreferCatsExpressions") {

  private val rules: List[SemanticRule] =
    List(
      new PreferCatsFunctions(),
      new PreferCatsSyntax(),
      new SimplifyCatsExpressions()
    )

  override def fix(implicit doc: SemanticDocument): Patch =
    rules.map(_.fix).asPatch
}

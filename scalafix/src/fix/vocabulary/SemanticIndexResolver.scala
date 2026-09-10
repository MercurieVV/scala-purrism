package fix.vocabulary

import scala.meta._
import scala.meta.internal.{semanticdb => s}

/** Resolves named types to fully-qualified names by reading this file's own
  * compiled `.semanticdb` payload directly -- the same route
  * `KleisliLiftScope`/`WidenScope` use for cross-file lookups. `docs/RULES.md`
  * covers why: `SemanticDocument.internal` is `private[scalafix]`, so a
  * `SyntacticRule` -- which never receives a `SemanticDocument` -- has no other
  * way to reach the compiler's own resolution.
  *
  * `RestrictVocabulary` uses this when a compiled payload exists for the file
  * being checked, and falls back to `ImportTableResolver` when it does not.
  */
final class SemanticIndexResolver private (
    occurrenceAt: Map[(Int, Int), List[String]],
    symbolInfo: Map[String, s.SymbolInformation]
) extends TypeResolver {

  /** The type's fully-qualified name (dotted, e.g. "cats.data.Kleisli"), read
    * from the occurrence the payload recorded at this position; `None` if the
    * payload has no occurrence there.
    */
  def resolve(tpe: Type): Option[String] =
    symbolAt(tpe).map(PatternList.normalize)

  /** True if the occurrence at this position is a type parameter, or an
    * abstract type member -- the same exemption `RestrictVocabulary`'s semantic
    * path already applies via `symbol.info` in `TypeWhitelistCheck`.
    */
  def isExempt(tpe: Type): Boolean =
    symbolAt(tpe).flatMap(symbolInfo.get).exists { info =>
      info.kind == s.SymbolInformation.Kind.TYPE_PARAMETER ||
      (info.kind == s.SymbolInformation.Kind.TYPE && isAbstract(info))
    }

  private def isAbstract(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.ABSTRACT.value) != 0

  /** The symbol at this position, disambiguated when several occurrences share
    * it.
    *
    * A context bound (`[G[_]: Sync]`) and a supertype's constructor call both
    * desugar to a synthetic occurrence anchored at the *same* range as an
    * ordinary type reference -- `Sync`'s position also carries the evidence
    * parameter's own symbol, and an `extends Base` also carries `Base`'s
    * constructor symbol. Preferring the candidate whose own simple name matches
    * the token's spelling picks the type reference in both cases, since a
    * synthetic occurrence's symbol never happens to share that name. When
    * nothing matches by name -- the common case, with exactly one candidate --
    * the sole occurrence is trusted regardless.
    */
  private def symbolAt(tpe: Type): Option[String] = {
    val (pos, name) = tpe match {
      case sel: Type.Select => (sel.name.pos, sel.name.value)
      case Type.Name(value) => (tpe.pos, value)
      case other            => (other.pos, other.syntax)
    }
    val candidates =
      occurrenceAt.getOrElse((pos.startLine, pos.startColumn), Nil)
    candidates
      .find(SemanticIndexResolver.simpleName(_) == name)
      .orElse(candidates.headOption)
  }
}

object SemanticIndexResolver {

  def apply(
      document: s.TextDocument,
      symbolInfo: Map[String, s.SymbolInformation]
  ): SemanticIndexResolver = {
    val occurrenceAt = document.occurrences.iterator
      .collect {
        case occurrence if occurrence.range.isDefined =>
          val range = occurrence.range.get
          (range.startLine, range.startCharacter) -> occurrence.symbol
      }
      .toList
      .groupMap(_._1)(_._2)
    new SemanticIndexResolver(occurrenceAt, symbolInfo)
  }

  /** The identifier a raw SemanticDB symbol string itself names, stripped of
    * every path/descriptor marker -- `cats/effect/package.Sync#` -> `Sync`,
    * `Owner#step().[G]` -> `G`, `Owner#`\`<init>\`().` -> `<init>`.
    */
  private def simpleName(symbol: String): String =
    if (symbol.endsWith("]")) {
      symbol.substring(symbol.lastIndexOf('[') + 1, symbol.length - 1)
    } else if (symbol.endsWith(")") && !symbol.endsWith("().")) {
      symbol.substring(symbol.lastIndexOf('(') + 1, symbol.length - 1)
    } else {
      val trimmed = symbol.stripSuffix("#").stripSuffix(".")
      val beforeParens =
        if (trimmed.endsWith(")"))
          trimmed.substring(0, trimmed.lastIndexOf('('))
        else trimmed
      lastSegment(beforeParens)
    }

  private def lastSegment(value: String): String = {
    val afterSlash = value.substring(value.lastIndexOf('/') + 1)
    val afterDot = afterSlash.substring(afterSlash.lastIndexOf('.') + 1)
    val afterHash = afterDot.substring(afterDot.lastIndexOf('#') + 1)
    afterHash.stripPrefix("`").stripSuffix("`")
  }
}

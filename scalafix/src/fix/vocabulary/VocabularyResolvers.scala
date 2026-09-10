package fix.vocabulary

import scala.meta.Source

import fix.opaque.SemanticdbIndex

/** Picks which [[TypeResolver]] `RestrictVocabulary` uses for one file: the
  * compiled payload, when the project's SemanticDB still has it for this exact
  * source text, or the import-table approximation otherwise -- stale or
  * entirely missing SemanticDB (a file mid-edit, or one that has never
  * compiled) falls back rather than trusting positions that may no longer line
  * up with the source.
  */
object VocabularyResolvers {

  def forSource(
      source: Source,
      text: String,
      index: SemanticdbIndex
  ): TypeResolver =
    matchingDocument(text, index) match {
      case Some(document) => SemanticIndexResolver(document, index.symbolInfo)
      case None           => ImportTableResolver.fromSource(source)
    }

  private def matchingDocument(text: String, index: SemanticdbIndex) = {
    val digest = SemanticdbIndex.md5(text)
    index.documents.find(_.md5 == digest)
  }
}

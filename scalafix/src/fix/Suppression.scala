package fix

import scala.meta._

import scalafix.v1.SemanticDocument

/** The opt-out marker every rewriting rule honours.
  *
  * Some code is deliberately un-idiomatic. A realtime audio callback with a
  * zero-allocation contract, a fold measured in bytes per chunk, a `while` over
  * a flat `Array` that must not perturb what it measures -- in all three a
  * lawful Cats rewrite is a regression, and no amount of semantic information
  * tells a rule so. Only the author knows.
  *
  * `// purrism:keep <reason>` says it. A marker suppresses:
  *   - the expression on the marker's own line (trailing form), and
  *   - the expression on the line below it (leading form), and
  *   - everything inside a definition whose own first line carries either form,
  *     so one marker covers a whole hot method.
  *
  * The reason text is not parsed. It exists for the next reader, which is the
  * point: a marker without one is a rule someone silenced, and a marker with
  * one is a decision someone made.
  */
private[fix] final class Suppression(markerLines: Set[Int]) {

  /** Whether `tree` sits under a marker, directly or through an enclosing
    * definition.
    */
  def suppresses(tree: Tree): Boolean =
    markerLines.nonEmpty && (marked(tree) || enclosingDefinitionMarked(tree))

  private def marked(tree: Tree): Boolean =
    tree.pos != Position.None && markerLines.contains(tree.pos.startLine)

  /** A marker on a definition covers the definition's body.
    *
    * Walking the parent chain rather than testing containment by position keeps
    * this to the tree scalafix handed us, and stops a marker inside one method
    * from reaching an unrelated expression that merely starts on the same line
    * of a different definition.
    */
  private def enclosingDefinitionMarked(tree: Tree): Boolean =
    tree.parent.exists {
      case parent: Defn => marked(parent) || enclosingDefinitionMarked(parent)
      case parent: Decl => marked(parent) || enclosingDefinitionMarked(parent)
      case parent       => enclosingDefinitionMarked(parent)
    }
}

private[fix] object Suppression {

  private val Marker = "purrism:keep"

  /** Never suppresses. For unit tests of rendering, where the marker is not
    * what is under test.
    */
  val none: Suppression = new Suppression(Set.empty)

  def forDocument(implicit doc: SemanticDocument): Suppression =
    new Suppression(markerLines(doc.tree))

  /** The lines a marker covers: the comment's own line, and the one after it.
    *
    * Read from `doc.tree.tokens` rather than from a re-parse of
    * `doc.input.text` -- `docs/RULES.md` requires every position a rule acts on
    * to come from the tree scalafix handed it, and a comment's line number is
    * as much a position as a patch anchor is.
    */
  def markerLines(tree: Tree): Set[Int] =
    tree.tokens
      .collect {
        case comment: Token.Comment if comment.value.contains(Marker) =>
          val line = comment.pos.startLine
          Set(line, line + 1)
      }
      .flatten
      .toSet
}

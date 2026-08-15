package fix

import scala.meta.inputs.Position

import scalafix.v1.ApplyTree
import scalafix.v1.FunctionTree
import scalafix.v1.IdTree
import scalafix.v1.MacroExpansionTree
import scalafix.v1.OriginalSubTree
import scalafix.v1.OriginalTree
import scalafix.v1.SelectTree
import scalafix.v1.SemanticDocument
import scalafix.v1.SemanticTree
import scalafix.v1.Symbol
import scalafix.v1.TypeApplyTree

/** SemanticDB reading shared by more than one rule.
  *
  * Cats syntax reaches a call site through an implicit conversion, so the
  * symbol a rule needs -- `cats/Functor.Ops#map().` rather than the receiver's
  * own `map` -- is not on the tree at all. It is in `doc.synthetics`, keyed by
  * the position the conversion was applied at. Recovering it is the difference
  * between "this receiver has a Cats Functor" and "this identifier is spelled
  * map", so both `PreferPolymorphicTypeclasses` and the Cats expression rules
  * need it.
  */
object SemanticSupport {

  final case class SyntheticEvidence(
      position: Position,
      symbols: List[Symbol]
  )

  /** Every synthetic in the document, paired with the source positions it was
    * applied at, in a deterministic order.
    */
  def syntheticEvidence(implicit
      doc: SemanticDocument
  ): List[SyntheticEvidence] =
    doc.synthetics
      .flatMap { synthetic =>
        val (positions, symbols) = flattenSynthetic(synthetic)
        positions.map(position =>
          SyntheticEvidence(position, symbols.distinct.sortBy(_.value))
        )
      }
      .toList
      .sortBy(evidence =>
        (
          evidence.position.start,
          evidence.position.end,
          // A newline cannot occur inside a SemanticDB symbol, so joining on
          // one keeps this tiebreak injective.
          evidence.symbols.map(_.value).mkString("\n")
        )
      )

  /** `base` plus every synthetic symbol whose position overlaps `span`, with
    * unresolved symbols dropped.
    */
  def symbolsAt(
      span: Position,
      base: Symbol,
      synthetics: List[SyntheticEvidence]
  ): List[Symbol] =
    (base :: synthetics
      .filter(evidence => positionsOverlap(span, evidence.position))
      .flatMap(_.symbols))
      .filter(!_.isNone)
      .distinct

  def positionsOverlap(left: Position, right: Position): Boolean =
    left != Position.None &&
      right != Position.None &&
      left.start <= right.end &&
      right.start <= left.end

  /** Stdlib companions that construct a value of a known type, mapped to that
    * type and the source-level name. Spelling is not identity: `Right` can be
    * shadowed by a local binding, so a rule that cares whether it is looking at
    * `scala.util.Right` must compare symbols from this table.
    */
  val stdlibConstructors: Map[Symbol, (Symbol, String)] = Map(
    Symbol("scala/None.") -> (Symbol("scala/Option#"), "None"),
    Symbol("scala/Some.") -> (Symbol("scala/Option#"), "Some"),
    Symbol("scala/package.None.") -> (Symbol("scala/Option#"), "None"),
    Symbol("scala/package.Some.") -> (Symbol("scala/Option#"), "Some"),
    Symbol("scala/package.Nil.") -> (
      Symbol("scala/collection/immutable/List#"),
      "Nil"
    ),
    Symbol("scala/package.`::`.") -> (
      Symbol("scala/collection/immutable/List#"),
      "::"
    ),
    Symbol("scala/collection/immutable/Nil.") -> (
      Symbol("scala/collection/immutable/List#"),
      "Nil"
    ),
    Symbol("scala/collection/immutable/::.") -> (
      Symbol("scala/collection/immutable/List#"),
      "::"
    ),
    Symbol("scala/collection/immutable/`::`.") -> (
      Symbol("scala/collection/immutable/List#"),
      "::"
    ),
    Symbol("scala/collection/immutable/$colon$colon.") -> (
      Symbol("scala/collection/immutable/List#"),
      "::"
    ),
    Symbol("scala/util/Left.") -> (Symbol("scala/util/Either#"), "Left"),
    Symbol("scala/util/Right.") -> (Symbol("scala/util/Either#"), "Right")
  )

  /** Aliases the compiler also emits for the same constructors, which
    * [[stdlibConstructors]] does not carry. Kept separate because that table
    * drives `PreferPolymorphicTypeclasses` decline decisions, and widening it
    * there would change which definitions that rule refuses to abstract.
    */
  private val stdlibConstructorAliases: Map[String, Set[String]] = Map(
    "Right" -> Set("scala/package.Right."),
    "Left" -> Set("scala/package.Left.")
  )

  /** Every constructor symbol carrying a given source-level name, e.g.
    * `"Right"` -> `scala/util/Right.` and `scala/package.Right.`.
    */
  def stdlibConstructorSymbols(name: String): Set[String] =
    stdlibConstructors.collect {
      case (symbol, (_, label)) if label == name => symbol.value
    }.toSet ++ stdlibConstructorAliases.getOrElse(name, Set.empty)

  private def flattenSynthetic(
      tree: SemanticTree
  ): (List[Position], List[Symbol]) =
    tree match {
      case IdTree(info) => (Nil, List(info.symbol))
      case ApplyTree(function, arguments) =>
        combine(function :: arguments)
      case SelectTree(qualifier, id) =>
        combine(List(qualifier, id))
      case TypeApplyTree(function, _) =>
        flattenSynthetic(function)
      case FunctionTree(parameters, body) =>
        combine(parameters :+ body)
      case MacroExpansionTree(beforeExpansion, _) =>
        flattenSynthetic(beforeExpansion)
      case OriginalTree(tree) =>
        (List(tree.pos), Nil)
      case OriginalSubTree(tree) =>
        (List(tree.pos), Nil)
      case _ => (Nil, Nil)
    }

  private def combine(
      trees: List[SemanticTree]
  ): (List[Position], List[Symbol]) =
    trees.foldLeft((List.empty[Position], List.empty[Symbol])) {
      case ((positions, symbols), tree) =>
        val (treePositions, treeSymbols) = flattenSynthetic(tree)
        (positions ++ treePositions, symbols ++ treeSymbols)
    }
}

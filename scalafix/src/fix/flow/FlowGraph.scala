package fix.flow

/** The value-flow model, shared by every rule that has to follow a change
  * beyond the place it starts.
  *
  * Deliberately policy-free. What counts as a boundary is not a property of the
  * graph but of the change being made: an opaque type can be wrapped and
  * unwrapped at the edge, so a boundary there is a conversion site, while a
  * container abstracted to `S[_]` cannot be turned back into a `Seq` at all, so
  * the same shape is a hard stop. The graph says where a value goes; each rule
  * says what that means for it.
  */

/** A path into a type's structure, as a list of type-argument indices.
  *
  * `Nil` is the type itself. Tuples are `scala/TupleN#` type references, so a
  * tuple slot is just an ordinary type-argument index -- no special case. For
  * `Kleisli[F, (Path, Path, String, Option[String], L), Unit]` the third input
  * tuple slot is `TypePath(List(1, 2))`, while `Option[String]` at slot 4 is
  * `TypePath(List(1, 3))` and its payload is `TypePath(List(1, 3, 0))`. Those
  * are distinct nodes, which is what keeps an `Option[String]` neighbour out of
  * the closure of a plain `String`.
  */
final case class TypePath(indices: List[Int]) {
  def /(index: Int): TypePath = TypePath(indices :+ index)
  def render: String =
    if (indices.isEmpty) "" else indices.mkString("[", ",", "]")
}

object TypePath {
  val root: TypePath = TypePath(Nil)
  implicit val ordering: Ordering[TypePath] =
    Ordering.by(path => path.indices.mkString(","))
}

/** A position in a source file, carried for diagnostics and patch anchoring. */
final case class Provenance(
    uri: String,
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int
) {
  def render: String = s"$uri:${startLine + 1}:${startColumn + 1}"
}

object Provenance {
  val unknown: Provenance = Provenance("<unknown>", 0, 0, 0, 0)
  implicit val ordering: Ordering[Provenance] =
    Ordering.by(p => (p.uri, p.startLine, p.startColumn))
}

/** Where a symbol comes from, which decides how a boundary is treated.
  *
  *   - `Project`: defined in one of the analysed SemanticDB documents, so its
  *     signature can be rewritten.
  *   - `Foreign`: no SymbolInformation in the document set -- a library or JDK
  *     symbol whose signature is fixed. Values must be unwrapped before
  *     crossing.
  *   - `Expression`: a synthetic node standing for a raw-primitive expression
  *     (a literal, an interpolation, a `.toString` call). Has no signature; it
  *     is where a value is born.
  */
enum Origin {
  case Project, Foreign, Expression
}

enum EdgeKind {
  case ArgToParam, ReturnToCaller, BodyToReturn, TupleSlot, Ascription,
    Reshape, HktPassthrough, FieldAlias, InferredVal, Override
}

/** A node in the flow graph: one type position of one symbol. */
final case class Node(symbol: String, path: TypePath) {
  def render: String = s"$symbol${path.render}"
}

object Node {
  implicit val ordering: Ordering[Node] =
    Ordering.by(node => (node.symbol, node.path))
}

/** A directed value-flow edge: a value moves from `from` to `to`. */
final case class Edge(
    from: Node,
    to: Node,
    kind: EdgeKind,
    at: Provenance
)

/** Everything the closure algorithm needs to know about the program, with no
  * SemanticDB types in sight so it can be driven by a fake in tests.
  */
trait Facts {

  /** All value-flow edges in the program. */
  def edges: List[Edge]

  /** Where `symbol` is defined, which decides boundary treatment. */
  def origin(symbol: String): Origin

  /** The type symbol at this node's path, if the path resolves. `None` means
    * the path does not exist in that signature, which makes the node
    * ineligible.
    */
  def typeAt(node: Node): Option[String]
}

/** A boundary where the value enters or leaves the opaque world. */

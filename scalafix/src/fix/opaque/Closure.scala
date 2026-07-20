package fix.opaque

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
final case class Boundary(
    node: Node,
    counterpart: Node,
    at: Provenance,
    kind: EdgeKind
)

/** A node the closure reached going forward, but which also receives a value
  * from somewhere the closure does not cover.
  *
  * Converting it would silently retype `intruder` as well, so by default the
  * node keeps the underlying type and the closure-side call sites unwrap with
  * `.value`. Listing `intruder.symbol` in `widen` pulls that source in instead,
  * letting the conversion continue through the node.
  *
  * Both readings are legitimate and the shapes are identical -- a branch name
  * and a base branch meeting in `ensureBranch(root, name)` look exactly like a
  * branch name and an unrelated ref meeting in `logRef(ref)`. Only the domain
  * decides, so this is reported rather than guessed.
  */
final case class MergePoint(
    node: Node,
    intruder: Node,
    at: Provenance,
    kind: EdgeKind
) {
  def message: String =
    s"${node.render} also receives ${intruder.render} at ${at.render}, which " +
      s"the closure does not cover; keeping the underlying type and " +
      s"unwrapping at the call site. Add \"${intruder.symbol}\" to `widen` to " +
      s"convert it too."
}

final case class ClosureResult(
    members: List[Node],
    genesis: List[Boundary],
    leaves: List[Boundary],
    mergePoints: List[MergePoint]
)

object Closure {

  /** Iteration cap for conflict eviction; exceeding it means a modelling bug.
    */
  private val MaxRounds = 10

  /** Take the transitive closure of the seeds along value flow, then classify
    * the frontier.
    *
    * This is a graph, not a tree: a parameter can receive values from several
    * places, and flow round a cycle (parameter to body, body to return, return
    * to a caller that feeds the parameter again). The multi-parent case is what
    * `MergePoint` exists for.
    *
    * Expansion is *forward* only -- from a value to where it flows. A node
    * whose every inbound value the closure covers is converted. A node that
    * also receives something from outside is demoted to a boundary, because
    * converting it would retype that other source too. Demotion cascades, so
    * the expansion re-runs until it settles.
    *
    * `widen` names symbols whose backward sources should be pulled in anyway,
    * turning a merge point into ordinary propagation.
    */
  def compute(
      seeds: Set[Node],
      facts: Facts,
      primitiveType: String,
      widen: Set[String] = Set.empty
  ): ClosureResult = {
    val allEdges = facts.edges
    // Only project symbols can become members: an Expression has no signature
    // to rewrite, and a Foreign signature is not ours to change. Both are
    // boundaries by construction, so they must stay outside the closure even
    // though their type matches.
    val eligible: Node => Boolean = node =>
      facts.typeAt(node).contains(primitiveType) &&
        facts.origin(node.symbol) == Origin.Project

    val outgoing: Map[Node, List[Edge]] = allEdges.groupBy(_.from)
    val incoming: Map[Node, List[Edge]] = allEdges.groupBy(_.to)

    val liveSeeds = seeds.filter(eligible)

    // Forward reachability: from a value to wherever it flows. Widened symbols
    // additionally admit their backward sources.
    def expand(demoted: Set[Node]): Set[Node] = {
      val visited =
        scala.collection.mutable.LinkedHashSet.from(liveSeeds.toList.sorted)
      val queue = scala.collection.mutable.Queue.from(visited)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        val forward = outgoing.getOrElse(current, Nil).map(_.to)
        val backward =
          if (widen.contains(current.symbol))
            incoming.getOrElse(current, Nil).map(_.from)
          else Nil
        (forward ++ backward).distinct.sorted.foreach { next =>
          if (
            !visited.contains(next) && !demoted.contains(next) && eligible(next)
          ) {
            visited += next
            queue.enqueue(next)
          }
        }
      }
      visited.toSet
    }

    // A node is demoted when a value the closure does not cover also flows into
    // it: converting it would retype that source too. Inbound expressions and
    // foreign values are not intruders -- those are genesis sites, where the
    // value is created and gets wrapped. Seeds are axioms and never demote.
    def mergePointsIn(closure: Set[Node]): List[MergePoint] =
      allEdges
        .filter { edge =>
          closure.contains(edge.to) &&
          !closure.contains(edge.from) &&
          !liveSeeds.contains(edge.to) &&
          !widen.contains(edge.from.symbol) &&
          facts.origin(edge.from.symbol) == Origin.Project &&
          facts.typeAt(edge.from).contains(primitiveType)
        }
        .map(edge => MergePoint(edge.to, edge.from, edge.at, edge.kind))
        .distinctBy(merge => (merge.node, merge.intruder))
        .sortBy(merge => (merge.node, merge.intruder))

    val (closure, mergePoints) = {
      def loop(
          demoted: Set[Node],
          found: List[MergePoint],
          round: Int
      ): (Set[Node], List[MergePoint]) = {
        val current = expand(demoted)
        val fresh =
          mergePointsIn(current).filterNot(merge =>
            demoted.contains(merge.node)
          )
        if (fresh.isEmpty) (current, found)
        else if (round >= MaxRounds)
          sys.error(
            s"opaque-type closure did not settle after $MaxRounds rounds; " +
              s"unresolved: ${fresh.map(_.node.render).mkString(", ")}"
          )
        else
          loop(demoted ++ fresh.map(_.node), found ++ fresh, round + 1)
      }
      loop(Set.empty, Nil, 0)
    }

    val boundaries = allEdges.filter(edge =>
      closure.contains(edge.from) != closure.contains(edge.to)
    )

    // Inbound from an expression: the value is born here, so wrap it.
    val genesis = boundaries
      .filter(edge =>
        closure.contains(edge.to) &&
          facts.origin(edge.from.symbol) == Origin.Expression
      )
      .map(edge => Boundary(edge.to, edge.from, edge.at, edge.kind))
      .distinctBy(b => (b.node, b.counterpart, b.at))
      .sortBy(b => (b.at, b.node))

    // Outbound into something whose signature we cannot change, or into a node
    // we evicted: unwrap before it crosses.
    val leaves = boundaries
      .filter(edge =>
        closure.contains(edge.from) &&
          facts.origin(edge.to.symbol) != Origin.Expression
      )
      .map(edge => Boundary(edge.from, edge.to, edge.at, edge.kind))
      .distinctBy(b => (b.node, b.counterpart, b.at))
      .sortBy(b => (b.at, b.node))

    ClosureResult(
      members = closure.toList.sorted,
      genesis = genesis,
      leaves = leaves,
      mergePoints = mergePoints.sortBy(merge => (merge.node, merge.intruder))
    )
  }
}

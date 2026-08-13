package fix.opaque

import fix.flow.Edge
import fix.flow.EdgeKind
import fix.flow.Facts
import fix.flow.Node
import fix.flow.Origin
import fix.flow.Provenance
import fix.flow.TypePath

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
    val eligible: Node => Boolean = node => {
      val orig = facts.origin(node.symbol)
      if (orig == Origin.Expression) true
      else facts.typeAt(node).contains(primitiveType) && orig == Origin.Project
    }

    val outgoingBySymbol: Map[String, List[Edge]] =
      allEdges.groupBy(_.from.symbol)
    val incomingBySymbol: Map[String, List[Edge]] =
      allEdges.groupBy(_.to.symbol)

    val liveSeeds = seeds.filter(eligible)

    // Forward reachability: from a value to wherever it flows. Widened symbols
    // additionally admit their backward sources.
    def expand(demoted: Set[Node]): Set[Node] = {
      val visited =
        scala.collection.mutable.LinkedHashSet.from(liveSeeds.toList.sorted)
      val queue = scala.collection.mutable.Queue.from(visited)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        val forward =
          outgoingBySymbol.getOrElse(current.symbol, Nil).flatMap { edge =>
            if (current.path.indices.startsWith(edge.from.path.indices)) {
              val suffix =
                current.path.indices.drop(edge.from.path.indices.length)
              Some(
                Node(edge.to.symbol, TypePath(edge.to.path.indices ++ suffix))
              )
            } else None
          }
        val backward = if (widen.contains(current.symbol)) {
          incomingBySymbol.getOrElse(current.symbol, Nil).flatMap { edge =>
            if (current.path.indices.startsWith(edge.to.path.indices)) {
              val suffix =
                current.path.indices.drop(edge.to.path.indices.length)
              Some(
                Node(
                  edge.from.symbol,
                  TypePath(edge.from.path.indices ++ suffix)
                )
              )
            } else None
          }
        } else Nil
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
    def mergePointsIn(closure: Set[Node]): List[MergePoint] = {
      val closureBySymbol = closure.groupBy(_.symbol)
      allEdges
        .flatMap { edge =>
          closureBySymbol
            .getOrElse(edge.to.symbol, Nil)
            .filter(node => node.path.indices.startsWith(edge.to.path.indices))
            .flatMap { node =>
              val suffix = node.path.indices.drop(edge.to.path.indices.length)
              val fromNode = Node(
                edge.from.symbol,
                TypePath(edge.from.path.indices ++ suffix)
              )
              val isMerge =
                !closure.contains(fromNode) &&
                  !liveSeeds.contains(node) &&
                  !widen.contains(fromNode.symbol) &&
                  facts.origin(fromNode.symbol) == Origin.Project &&
                  facts.typeAt(fromNode).contains(primitiveType)
              if (isMerge) Some(MergePoint(node, fromNode, edge.at, edge.kind))
              else None
            }
        }
        .distinctBy(merge => (merge.node, merge.intruder))
        .sortBy(merge => (merge.node, merge.intruder))
    }

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

    val boundaries = {
      val closureBySymbol = closure.groupBy(_.symbol)
      allEdges.flatMap { edge =>
        val fromPaths = closureBySymbol
          .getOrElse(edge.from.symbol, Nil)
          .filter(n => n.path.indices.startsWith(edge.from.path.indices))
          .map(_.path.indices.drop(edge.from.path.indices.length))
        val toPaths = closureBySymbol
          .getOrElse(edge.to.symbol, Nil)
          .filter(n => n.path.indices.startsWith(edge.to.path.indices))
          .map(_.path.indices.drop(edge.to.path.indices.length))

        (fromPaths.toSeq ++ toPaths.toSeq).distinct.flatMap { suffix =>
          val fromNode =
            Node(edge.from.symbol, TypePath(edge.from.path.indices ++ suffix))
          val toNode =
            Node(edge.to.symbol, TypePath(edge.to.path.indices ++ suffix))
          if (closure.contains(fromNode) != closure.contains(toNode)) {
            Some(Boundary(toNode, fromNode, edge.at, edge.kind))
          } else None
        }
      }
    }

    // Inbound: the value enters the opaque world here, so wrap it.
    val genesis = boundaries
      .filter(edge => closure.contains(edge.node))
      .map(edge => Boundary(edge.node, edge.counterpart, edge.at, edge.kind))
      .distinctBy(b => (b.node, b.counterpart, b.at))
      .sortBy(b => (b.at, b.node))

    def isPassthroughSymbol(symbol: String): Boolean = {
      val clean = symbol.stripPrefix("foreign:")
      val methodStart = clean.indexOf('#')
      if (methodStart >= 0) {
        val methodEnd = clean.indexOf("().", methodStart)
        if (methodEnd >= 0) {
          val methodName = clean.substring(methodStart + 1, methodEnd)
          GraphBuilder.PassthroughMethods.contains(methodName)
        } else false
      } else false
    }

    // Outbound into something whose signature we cannot change, or into a node
    // we evicted: unwrap before it crosses.
    val leaves = boundaries
      .filter(edge =>
        closure.contains(edge.counterpart) &&
          facts.origin(edge.node.symbol) != Origin.Expression &&
          !isPassthroughSymbol(edge.node.symbol)
      )
      .map(edge => Boundary(edge.counterpart, edge.node, edge.at, edge.kind))
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

package io.github.mercurievv.purrism.graphmodel

import scala.collection.mutable

/** Fills in each node's derived graph properties -- `layer`, `ca`, `ce`,
  * `instability`, `centrality` -- from the model's own edges. `Contains` edges
  * are scaffolding (parent/child ownership), not a dependency, so they're
  * excluded from all of these; every other edge kind counts.
  */
object GraphMetrics:

  private val dependencyKinds: Set[EdgeKind] =
    EdgeKind.values.toSet - EdgeKind.Contains

  def compute(model: GraphModel): GraphModel =
    val depEdges = model.edges.filter(e => dependencyKinds.contains(e.kind))
    val nodeIds = model.nodes.map(_.id).toSet
    val relevant =
      depEdges.filter(e => nodeIds.contains(e.from) && nodeIds.contains(e.to))

    val ca = mutable.Map.empty[String, Int].withDefaultValue(0)
    val ce = mutable.Map.empty[String, Int].withDefaultValue(0)
    val outNeighbors = mutable.Map.empty[String, mutable.Set[String]]
    val inNeighbors = mutable.Map.empty[String, mutable.Set[String]]
    relevant.foreach { e =>
      ce(e.from) += 1
      ca(e.to) += 1
      outNeighbors.getOrElseUpdate(e.from, mutable.Set.empty) += e.to
      inNeighbors.getOrElseUpdate(e.to, mutable.Set.empty) += e.from
    }

    val layers = computeLayers(nodeIds, outNeighbors)
    val centrality = computePageRank(nodeIds, outNeighbors, inNeighbors)

    val updated = model.nodes.map { n =>
      val caN = ca(n.id)
      val ceN = ce(n.id)
      val instability =
        if caN + ceN == 0 then 0.0 else ceN.toDouble / (caN + ceN)
      n.copy(
        layer = layers.getOrElse(n.id, 0),
        ca = caN,
        ce = ceN,
        instability = instability,
        centrality = centrality.getOrElse(n.id, 0.0)
      )
    }
    GraphModel(updated, model.edges)

  /** Longest dependency-chain depth per node, after condensing cycles (Tarjan
    * SCC) into single units so a cycle doesn't produce an infinite/undefined
    * longest path. All members of one SCC share that SCC's layer. Layer 0 = an
    * SCC with no outgoing edges to other SCCs (a foundation/leaf-dependency
    * unit); layers count how many SCC-hops sit strictly below.
    */
  private def computeLayers(
      nodeIds: Set[String],
      outNeighbors: mutable.Map[String, mutable.Set[String]]
  ): Map[String, Int] =
    val sccOf = tarjanScc(nodeIds, outNeighbors)
    val sccIds = sccOf.values.toSet

    val sccOutEdges: Map[Int, Set[Int]] =
      sccIds.map { scc =>
        val members = sccOf.iterator.collect {
          case (id, s) if s == scc => id
        }.toSet
        val targets = members
          .flatMap(m => outNeighbors.getOrElse(m, mutable.Set.empty))
          .flatMap(sccOf.get)
          .filter(_ != scc)
        scc -> targets
      }.toMap

    val memo = mutable.Map.empty[Int, Int]
    def longestFrom(scc: Int, visiting: Set[Int]): Int =
      memo.getOrElse(
        scc, {
          val depth =
            if visiting.contains(scc) then
              0 // defensive; condensation is acyclic so shouldn't happen
            else
              val children = sccOutEdges.getOrElse(scc, Set.empty)
              if children.isEmpty then 0
              else 1 + children.map(c => longestFrom(c, visiting + scc)).max
          memo(scc) = depth
          depth
        }
      )

    sccOf.map { case (id, scc) => id -> longestFrom(scc, Set.empty) }.toMap

  private def tarjanScc(
      nodeIds: Set[String],
      outNeighbors: mutable.Map[String, mutable.Set[String]]
  ): Map[String, Int] =
    var index = 0
    val indices = mutable.Map.empty[String, Int]
    val lowlink = mutable.Map.empty[String, Int]
    val onStack = mutable.Set.empty[String]
    val stack = mutable.ArrayDeque.empty[String]
    val result = mutable.Map.empty[String, Int]
    var sccCounter = 0

    def strongConnect(v: String): Unit =
      // Iterative Tarjan to avoid stack overflow on large graphs: an explicit
      // work-list of (node, child-iterator) frames stands in for the call stack.
      case class Frame(node: String, children: Iterator[String])
      val work = mutable.ArrayDeque.empty[Frame]

      indices(v) = index
      lowlink(v) = index
      index += 1
      stack.append(v)
      onStack += v
      work.append(
        Frame(v, outNeighbors.getOrElse(v, mutable.Set.empty).iterator)
      )

      while work.nonEmpty do
        val frame = work.last
        if frame.children.hasNext then
          val w = frame.children.next()
          if !indices.contains(w) then
            indices(w) = index
            lowlink(w) = index
            index += 1
            stack.append(w)
            onStack += w
            work.append(
              Frame(w, outNeighbors.getOrElse(w, mutable.Set.empty).iterator)
            )
          else if onStack.contains(w) then
            lowlink(frame.node) = math.min(lowlink(frame.node), indices(w))
        else
          work.removeLast()
          if work.nonEmpty then
            val parent = work.last.node
            lowlink(parent) = math.min(lowlink(parent), lowlink(frame.node))
          if lowlink(frame.node) == indices(frame.node) then
            var w = ""
            while
              w = stack.removeLast()
              onStack -= w
              result(w) = sccCounter
              w != frame.node
            do ()
            sccCounter += 1

    nodeIds.foreach(v => if !indices.contains(v) then strongConnect(v))
    result.toMap

  /** Simple PageRank over the dependency graph: an edge `A -> B` ("A depends on
    * B") is read as a vote from A to B, so nodes many others depend on
    * accumulate rank -- matching "important symbols are the ones important
    * symbols depend on".
    */
  private def computePageRank(
      nodeIds: Set[String],
      outNeighbors: mutable.Map[String, mutable.Set[String]],
      inNeighbors: mutable.Map[String, mutable.Set[String]]
  ): Map[String, Double] =
    val n = nodeIds.size
    if n == 0 then Map.empty
    else
      val damping = 0.85
      val base = (1 - damping) / n
      var ranks = nodeIds.map(_ -> 1.0 / n).toMap
      for _ <- 1 to 50 do
        ranks = nodeIds.map { id =>
          val incoming = inNeighbors.getOrElse(id, mutable.Set.empty)
          val sum = incoming.iterator.map { src =>
            val outDeg = outNeighbors.getOrElse(src, mutable.Set.empty).size
            if outDeg == 0 then 0.0 else ranks.getOrElse(src, 0.0) / outDeg
          }.sum
          id -> (base + damping * sum)
        }.toMap
      ranks

package io.github.mercurievv.purrism.graphmodel

/** Collapses a full [[GraphModel]] down to just its `Module` nodes and the
  * `ModuleDependsOn` edges between them -- the view the Three.js panel actually
  * renders. A module-pair edge weight is the count of underlying entity-level
  * edges (of any kind except `Contains`, which is intra-module scaffolding, not
  * a dependency) that cross between the two modules. Test-ish modules
  * (fixtures, `test`/`testInput`/`testOutput` submodules) are excluded for now
  * -- noisy for an architecture overview; revisit once there's a reason to show
  * them.
  */
object ModuleProjection:

  private def isTestModule(moduleName: String): Boolean =
    moduleName.split("/").lastOption.exists(_.toLowerCase.contains("test"))

  def project(model: GraphModel): GraphModel =
    val moduleNodes = model.nodes.filter(n =>
      n.kind == NodeKind.Module && !isTestModule(n.name)
    )
    val keptModuleNames = moduleNodes.map(_.name).toSet
    val moduleOfNode: Map[String, String] =
      model.nodes
        .map(n => n.id -> n.module)
        .filter { case (_, m) => keptModuleNames.contains(m) }
        .toMap

    val counts: Map[(String, String), Int] =
      model.edges
        .filter(_.kind != EdgeKind.Contains)
        .flatMap { e =>
          for
            fromM <- moduleOfNode.get(e.from)
            toM <- moduleOfNode.get(e.to)
            if fromM != toM
          yield (fromM, toM) -> e.weight
        }
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).sum)
        .toMap

    val edges = counts.iterator.map { case ((from, to), w) =>
      Edge(s"module:$from", s"module:$to", EdgeKind.ModuleDependsOn, w)
    }.toVector

    GraphModel(moduleNodes, edges)

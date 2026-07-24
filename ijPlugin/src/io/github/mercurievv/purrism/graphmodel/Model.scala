package io.github.mercurievv.purrism.graphmodel

/** The intermediate representation between SemanticDB and the Three.js viewer:
  * a typed, multi-relational graph over a project's own build-tool modules,
  * types, methods and values. [[GraphModelBuilder]] populates it from a
  * `SemanticIndex`; [[ModuleProjection]] collapses it down to the module-level
  * view the UI actually renders.
  */
enum NodeKind derives upickle.default.ReadWriter:
  case Module, ClassDef, TraitDef, ObjectDef, TypeAlias, MethodDef, ValueDef

/** Distinct relationship kinds observed between nodes. `ModuleDependsOn` only
  * ever appears in the output of [[ModuleProjection]] -- it is an aggregate,
  * not something read directly off SemanticDB.
  */
enum EdgeKind derives upickle.default.ReadWriter:
  case Contains, Extends, Calls, TypeReference, Implicit, ModuleDependsOn

/** One graph node.
  *
  * @param id
  *   the SemanticDB symbol for entity nodes, or `module:<name>` for module
  *   nodes
  * @param module
  *   the owning module's name (for a `Module` node, its own name)
  * @param signature
  *   rendered signature text, populated for `MethodDef`/`ValueDef` nodes
  * @param sourceUri
  *   the definition's source-relative path, when known
  */
final case class Node(
    id: String,
    kind: NodeKind,
    name: String,
    module: String,
    signature: Option[String] = None,
    sourceUri: Option[String] = None
) derives upickle.default.ReadWriter

/** One directed relationship, with `weight` counting how many raw SemanticDB
  * observations collapsed into it (e.g. how many call sites, or -- at module
  * granularity -- how many underlying entity-level edges).
  */
final case class Edge(from: String, to: String, kind: EdgeKind, weight: Int = 1)
    derives upickle.default.ReadWriter

final case class GraphModel(nodes: Vector[Node], edges: Vector[Edge])
    derives upickle.default.ReadWriter

object GraphJson:
  def toJson(model: GraphModel): String = upickle.default.write(model)

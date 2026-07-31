package io.github.mercurievv.purrism.graphmodel

/** The intermediate representation between SemanticDB and the Three.js viewer:
  * a typed, multi-relational graph over a project's own build-tool modules,
  * types, methods and values. [[GraphModelBuilder]] populates it from a
  * `SemanticIndex`; [[ModuleProjection]] collapses it down to the module-level
  * view the UI actually renders.
  */
enum NodeKind derives upickle.default.ReadWriter:
  case Module, File, ClassDef, TraitDef, ObjectDef, TypeAlias, MethodDef,
    ValueDef

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
  * @param layer
  *   longest dependency-chain depth after cycles are condensed; 0 = a
  *   foundation node, larger = sits above deeper dependencies. Set by
  *   [[GraphMetrics]].
  * @param ca
  *   afferent coupling -- number of incoming dependency edges. Set by
  *   [[GraphMetrics]].
  * @param ce
  *   efferent coupling -- number of outgoing dependency edges. Set by
  *   [[GraphMetrics]].
  * @param instability
  *   `ce / (ca + ce)`, 0..1; 0 = stable/foundation-like, 1 =
  *   leaf/consumer-like. Set by [[GraphMetrics]].
  * @param centrality
  *   PageRank-style importance over the dependency graph; higher = more
  *   structurally central. Set by [[GraphMetrics]].
  */
final case class Node(
    id: String,
    kind: NodeKind,
    name: String,
    module: String,
    signature: Option[String] = None,
    sourceUri: Option[String] = None,
    layer: Int = 0,
    ca: Int = 0,
    ce: Int = 0,
    instability: Double = 0.0,
    centrality: Double = 0.0
) derives upickle.default.ReadWriter

/** One directed relationship, with `weight` counting how many raw SemanticDB
  * observations collapsed into it (e.g. how many call sites, or -- at module
  * granularity -- how many underlying entity-level edges).
  */
final case class Edge(from: String, to: String, kind: EdgeKind, weight: Int = 1)
    derives upickle.default.ReadWriter

final case class GraphModel(nodes: Vector[Node], edges: Vector[Edge])
    derives upickle.default.ReadWriter

/** What the Three.js panel actually receives: the module-level projection for
  * the always-visible view, plus the raw `File` nodes (keyed by their `module`
  * field) so the UI can reveal a module's files on demand without a round trip.
  * `fileContainsEdges` are the `Contains` edges linking a file id to the
  * class/trait/object/type-alias ids it defines, so the UI can group those
  * `classes` by owning file and render them as the file rectangle's content --
  * classes/types no longer get their own oriented placement, since they live
  * inside their file's box instead. `classEdges` are the entity-level
  * dependency edges (`Extends`/`Calls`/`TypeReference`/`Implicit`) with both
  * endpoints among `classes`; the UI projects each one onto the pair of owning
  * files (via `fileContainsEdges`) to draw arrows between files, the same way
  * [[ModuleProjection]] projects entity edges onto modules.
  */
final case class GraphPayload(
    modules: GraphModel,
    files: Vector[Node] = Vector.empty,
    fileContainsEdges: Vector[Edge] = Vector.empty,
    classes: Vector[Node],
    classEdges: Vector[Edge] = Vector.empty
) derives upickle.default.ReadWriter

object GraphJson:
  def toJson(model: GraphModel): String = upickle.default.write(model)
  def toJson(payload: GraphPayload): String = upickle.default.write(payload)

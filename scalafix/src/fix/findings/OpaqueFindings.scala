package fix.findings

import fix.Finding

/** Finding kinds of `PropagateOpaqueType`. */
object OpaqueFindings {

  val MergePoint: Finding = Finding(
    "PropagateOpaqueType",
    "merge-point",
    "Merge point outside the closure",
    "A node the closure reached also receives a value the closure does not cover, so it keeps the underlying type and the call site unwraps.",
    "Add the reported symbol to `widen` if that value belongs in the conversion too, otherwise leave the merge point unwrapped; then re-run `<module>.fix`.",
    "#propagateopaquetype"
  )

  val StaleSemanticdb: Finding = Finding(
    "PropagateOpaqueType",
    "stale-semanticdb",
    "SemanticDB out of date",
    "The SemanticDB payload for this file no longer matches the source on disk, so a closure computed from it would be wrong and the file was left unchanged.",
    "Recompile to regenerate SemanticDB and re-run `<module>.fix`.",
    "#propagateopaquetype"
  )

  val findings: List[Finding] = List(MergePoint, StaleSemanticdb)
}

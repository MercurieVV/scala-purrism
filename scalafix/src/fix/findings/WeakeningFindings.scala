package fix.findings

import fix.Finding

/** Finding kinds of `TypeclassWeakening`. */
object WeakeningFindings {

  val AmbiguousWeakening: Finding = Finding(
    "TypeclassWeakening",
    "ambiguous-weakening",
    "Several weakest typeclasses fit the bound",
    "The body needs less than the bound, but two or more typeclasses below it cover those needs and neither is weaker than the other, so the bound is kept rather than guessed or split.",
    "Replace the bound by hand with the reported option that fits the def's callers, or keep it; then re-run `<module>.fix`.",
    "#typeclassweakening"
  )

  val findings: List[Finding] = List(AmbiguousWeakening)
}

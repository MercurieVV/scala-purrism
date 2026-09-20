package fix.findings

import fix.Finding

/** Finding kinds of `PreferCatsFunctions`: the three decline rules D1-D3 of
  * docs/PREFER_CATS_FUNCTIONS.md §3.
  */
object CatsFunctionFindings {

  val AmbiguousCatsMatch: Finding = Finding(
    "PreferCatsFunctions",
    "ambiguous-cats-match",
    "Body matches more than one Cats function",
    "The normalized body matches more than one public Cats function and ranking (public > in-scope > shortest) did not resolve a unique winner, so the rule did not rewrite.",
    "Pick the Cats function you mean and call it directly, or narrow the body so only one matches; see docs/PREFER_CATS_FUNCTIONS.md §3-4 (D1), then compile.",
    "#cats-expressions"
  )

  val PrivateCatsMatch: Finding = Finding(
    "PreferCatsFunctions",
    "private-cats-match",
    "Body matches only a private Cats symbol",
    "The body matches only a private or internal Cats implementation detail with no public API of the same normalized shape, and the rule never rewrites to a non-public symbol.",
    "Keep the body, or express it through a public Cats function; see docs/PREFER_CATS_FUNCTIONS.md §3 (D2).",
    "#cats-expressions"
  )

  val MissingTypeclassEvidence: Finding = Finding(
    "PreferCatsFunctions",
    "missing-typeclass-evidence",
    "Cats match needs typeclass evidence not in scope",
    "The matching Cats function requires a typeclass constraint that is not derivable from the enclosing parameter list, so the rewrite would not compile.",
    "Add the required typeclass constraint (for example a context bound) to the enclosing definition and re-run `<module>.fix`; see docs/PREFER_CATS_FUNCTIONS.md §3 (D3).",
    "#cats-expressions"
  )

  val findings: List[Finding] =
    List(AmbiguousCatsMatch, PrivateCatsMatch, MissingTypeclassEvidence)
}

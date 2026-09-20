package fix.findings

import fix.Finding

/** Finding kinds of `PreferArrow`: a Kleisli body the rule saw and declined,
  * reported so a reader knows the shape was seen and rejected on purpose, not
  * merely never matched.
  */
object ArrowFindings {

  val ReadabilityBudget: Finding = Finding(
    "PreferArrow",
    "readability-budget",
    "Kleisli body declined by the readability budget",
    "The body could be written point-free, but the rendered composition would read worse than the hand-threaded original, so the rule declined.",
    "Leave the body as it is, or split it into named Kleislis whose composition stays readable; re-run `<module>.fix` to see whether the rule now rewrites it.",
    "#preferarrow"
  )

  val FanOutShadowedInput: Finding = Finding(
    "PreferArrow",
    "fan-out-shadowed-input",
    "Fan-out input shadowed in an inner scope",
    "Both branches call `.run`/`.apply` with an argument spelled like the arrow's input, but the second resolves to a different binding, so under `&&&` the two Kleislis would no longer run on the same input.",
    "Rename the inner binding so it no longer shadows the arrow's input, or pass the input explicitly; then re-run `<module>.fix`.",
    "#preferarrow"
  )

  val UnrecognisedBody: Finding = Finding(
    "PreferArrow",
    "unrecognised-body",
    "Kleisli body the parser did not recognise",
    "The body has no monadic spine the Arrow parser understands, so no point-free form was attempted; this is reported only under `PreferArrow.reportSkips`.",
    "Compare the reported shape with the recognised entries in docs/ARROW_PATTERNS.md and restructure the body into a `for`/`flatMap` spine over the input, or leave it and turn `reportSkips` off.",
    "#preferarrow"
  )

  val findings: List[Finding] =
    List(ReadabilityBudget, FanOutShadowedInput, UnrecognisedBody)
}

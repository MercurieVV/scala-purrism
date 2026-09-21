package fix

import scalafix.lint.{Diagnostic, LintSeverity}
import scala.meta.inputs.Position

/** One finding KIND a rule can report. `code` (= `rule.kind`) is what scalafix
  * prints as the lint id and what an agent harness joins instructions on;
  * `explanation` is appended to every message for humans; `instruction` is the
  * repair guidance published in `findings.tsv`. Codes are append-only
  * (docs/MAINTENANCE.md).
  */
final case class Finding(
    rule: String,
    kind: String,
    title: String,
    explanation: String,
    instruction: String,
    doc: String
) {
  def code: String = s"$rule.$kind"
}

object FindingCatalog {
  val all: List[Finding] = findings.Families.all.sortBy(_.code)
  val byCode: Map[String, Finding] = all.map(f => f.code -> f).toMap
  def get(code: String): Option[Finding] = byCode.get(code)

  /** Codes no fixture can trigger, with the reason. The coverage suite skips
    * these — and asserts each one is still catalogued and still has NO fixture,
    * so the list cannot rot. Removing an entry is the way to re-require a
    * fixture.
    */
  val unfixturable: Map[String, String] = Map(
    "PreferCatsFunctions.private-cats-match" ->
      "the index generator only emits entries with a render template, so the private-only branch is unreachable",
    "PreferCatsFunctions.ambiguous-cats-match" ->
      "the only tie-eligible pair (reduceMapA/reduceMapM) has a curried body decideByPattern cannot bucket",
    "PropagateOpaqueType.stale-semanticdb" ->
      "reachable only with a stale build cache; a single-pass test run always recompiles testInput first"
  )

  /** `<text>. <explanation>` — the human reads the finding first; scalafix adds
    * `[rule.kind]` itself.
    */
  def messageOf(finding: Finding, text: String): String = {
    val t = text.trim
    val sep = if (t.endsWith(".")) " " else ". "
    s"$t$sep${finding.explanation}"
  }
}

/** The one Diagnostic every purrism rule emits. `categoryID = kind` makes the
  * lint id `Rule.kind`.
  */
final case class FindingDiagnostic(
    finding: Finding,
    override val position: Position,
    text: String
) extends Diagnostic {
  override def message: String = FindingCatalog.messageOf(finding, text)
  override def categoryID: String = finding.kind
  override def severity: LintSeverity = LintSeverity.Warning
}

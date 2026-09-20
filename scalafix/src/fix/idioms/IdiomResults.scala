package fix.idioms

import scala.meta._

import fix.Finding

/** A rewrite an idiom rule wants to make.
  *
  * `needsCatsSyntax` is per-rewrite rather than per-rule: `Either.catchNonFatal
  * (...).void` needs `cats.syntax.all.*` in scope, while a plain
  * `Option(...).fold(...)` does not, and adding an unused wildcard import to a
  * file compiled with `-Wunused:imports` turns a rewrite into a build failure.
  */
private[fix] final case class IdiomRewrite(
    tree: Tree,
    replacement: String,
    needsCatsSyntax: Boolean = false,
    needsNonFatal: Boolean = false,
    needsUsing: Boolean = false
)

/** Something an idiom rule recognised but will not rewrite.
  *
  * Reported rather than edited, per `docs/RULES.md`: a check that cannot be
  * rewritten safely reports a diagnostic instead of producing a partial edit.
  * `finding` is the catalogued kind; `text` is the rule's own short wording,
  * which `FindingDiagnostic` follows with the finding's explanation.
  */
private[fix] final case class IdiomFinding(
    tree: Tree,
    finding: Finding,
    text: String
)

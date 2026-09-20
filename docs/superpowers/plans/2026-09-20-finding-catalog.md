# Finding Catalog Implementation Plan (purrism half of the diagnostic→instruction table)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every purrism lint carries a stable finding code and a human explanation, and purrism publishes one machine-readable table (code → title, explanation, instruction, doc anchor) that agents' harnesses vendor and humans read on the docs site.

**Architecture:** A single `FindingCatalog` (Scala) is the source of truth. One `FindingDiagnostic(finding, position, text)` replaces the nine ad-hoc `Diagnostic` classes; it sets scalafix's `categoryID` to the finding's kind, so scalafix itself prints the code as its lint id — `[PreferEffectIdioms.manual-resource]` — and the golden fixtures' `// assert: Rule.kind` markers assert codes for free. A Mill command renders the catalog to `docs/findings.tsv` + `scalafix/resources/purrism/findings.tsv` with a `# purrism <version>` header, a check task fails CI when the committed copies are stale, and mdoc renders a "Findings" docs page from the same TSV.

**Tech Stack:** Scala 3 (`Versions.scala3`), Mill 1.1.7 (`build.mill`), scalafix 0.14.x (`Diagnostic`, `LintSeverity`, `Patch.lint`), scalafix-testkit (`MunitSemanticRuleSuite`, `// assert:` markers), munit, mdoc (`docs` module), MiMa (`scalafix.mimaReportBinaryIssues`).

**Spec:** `/Users/viktorskalinins/IdeaProjects/my/scala-coding-guidelines/docs/superpowers/specs/2026-09-20-diagnostic-instruction-table-design.md` — §1 (purrism), §3 (versioning contract, CI), §4 (tests). Read it first. **One deviation, ruled here:** the spec writes codes as `Rule/kind` with a hand-appended `[code]` suffix in the message; this plan uses scalafix's own lint id — code = `Rule.kind` (dot), emitted via `categoryID`, so the CLI/Mill output already prints `[Rule.kind]` and no suffix is hand-built. Message text = `<text>. <explanation>`. The TSV `code` column is `Rule.kind`. The harness plan reads the same shape.

## Global Constraints

- **Branch + worktree** from `master` (`superpowers:using-git-worktrees`); never commit on `master`.
- **Build commands:** `./mill scalafix.compile`, `./mill scalafix.test`, `./mill docs.run`, `./mill scalafix.mimaReportBinaryIssues` — these are what CI runs (`.github/workflows/ci.yml`). All four must be green before the last commit.
- **Codes:** `^[A-Z][A-Za-z0-9]*\.[a-z0-9]+(-[a-z0-9]+)*$` — `<RuleName>.<kebab-kind>`; `RuleName` must be an existing rule's `name`. Append-only; renaming a code is a breaking change (documented in `docs/MAINTENANCE.md`).
- **Fields:** `title` ≤ 60 chars; `explanation` one sentence ending in `.`; `instruction` 1–3 imperative sentences; `doc` an anchor present in `docs/index.md` (`#effect-boundaries`). All non-empty, no TAB, no newline.
- **Message format:** `FindingDiagnostic.message == s"$text. $explanation"` when `text` does not already end with `.`, else `s"$text $explanation"`; never contains the code (scalafix prints it as the lint id).
- **TSV format:** first line `# purrism <version>`, then one row per finding, columns `code<TAB>rule<TAB>title<TAB>explanation<TAB>instruction<TAB>doc`, sorted by `code`, `\n` line endings, UTF-8.
- **MiMa:** removing the old public `*Diagnostic` case classes is a binary-incompatible change to the artifact's internals. Ruled: add `ProblemFilters.exclude[MissingClassProblem]("fix.*Diagnostic")`-style filters in `build.mill`'s `mimaBinaryIssueFilters` for exactly the classes removed, with a comment that lint diagnostics are not API. If `mimaBinaryIssueFilters` does not exist yet, add it to the `scalafix` object.
- **Commit messages:** plain; no `Co-Authored-By`; end with `Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU`.

---

## File Structure

| File | Responsibility after this plan |
|---|---|
| `scalafix/src/fix/FindingCatalog.scala` | `Finding`, `FindingCatalog.all/byCode/rules`, `FindingDiagnostic` |
| `scalafix/src/fix/findings/*.scala` | catalog entries grouped by rule family (one object per family, e.g. `IdiomFindings`, `ArrowFindings`), each a `List[Finding]` — keeps the catalog file small |
| `scalafix/src/fix/{IdiomRules,PreferArrow,PreferCatsFunctions,PreferPolymorphicCollections,PreferPolymorphicTypeclasses,PropagateOpaqueType}.scala` | `Patch.lint(FindingDiagnostic(...))` only; old `*Diagnostic` classes deleted |
| `scalafix/src/fix/idioms/*.scala` | `IdiomFinding(tree, finding: Finding)` (was `message: String`) |
| `scalafix/testInput/src/**` | `// assert: Rule.kind` markers |
| `scalafix/test/src/fix/FindingCatalogSuite.scala` | catalog invariants + message rendering |
| `scalafix/test/src/fix/FindingCoverageSuite.scala` | every code asserted in some fixture; every asserted code catalogued |
| `build.mill` | `scalafix.findingsTsv` (generate), `scalafix.findings` (write both copies), `scalafix.findingsCheck` (stale → fail); MiMa filters |
| `docs/findings.tsv`, `scalafix/resources/purrism/findings.tsv` | the published table (committed, generated) |
| `docs/findings.md` + `docs` module | the "Findings" page rendered from the TSV |
| `docs/MAINTENANCE.md`, `.github/workflows/ci.yml`, `release.yml` | append-only rule; `findingsCheck` in CI |

---

### Task 1: `Finding`, `FindingCatalog`, `FindingDiagnostic` and their invariants

**Files:**
- Create: `scalafix/src/fix/FindingCatalog.scala`
- Create: `scalafix/src/fix/findings/Families.scala` (an empty-for-now registry the family objects register into)
- Test: `scalafix/test/src/fix/FindingCatalogSuite.scala`

**Interfaces:**
- Produces:
  ```scala
  package fix
  final case class Finding(rule: String, kind: String, title: String, explanation: String, instruction: String, doc: String) {
    def code: String = s"$rule.$kind"
  }
  object FindingCatalog {
    val all: List[Finding]                  // = findings.Families.all, sorted by code
    val byCode: Map[String, Finding]
    def get(code: String): Option[Finding]
    def messageOf(finding: Finding, text: String): String
  }
  final case class FindingDiagnostic(finding: Finding, position: scala.meta.inputs.Position, text: String) extends scalafix.lint.Diagnostic {
    override def message: String = FindingCatalog.messageOf(finding, text)
    override def categoryID: String = finding.kind
    override def severity: LintSeverity = LintSeverity.Warning
  }
  ```
  `findings.Families.all: List[Finding]` concatenates each family object's `findings` (added in Task 2).

- [ ] **Step 1: Write the failing test**

`scalafix/test/src/fix/FindingCatalogSuite.scala`:
```scala
package fix

import scala.meta.inputs.{Input, Position}

final class FindingCatalogSuite extends munit.FunSuite {
  private val sample = Finding("PreferEffectIdioms", "manual-resource", "Manual acquire/release",
    "A resource acquired and released by hand is not tracked by the effect system.",
    "Wrap the acquire in Resource.make(acquire)(release) and thread it with use.", "#effect-boundaries")
  private val pos = Position.Range(Input.String("x"), 0, 1)

  test("code is rule.kind") { assertEquals(sample.code, "PreferEffectIdioms.manual-resource") }
  test("message appends the explanation after a period") {
    assertEquals(FindingDiagnostic(sample, pos, "use Resource for acquire/release").message,
      "use Resource for acquire/release. A resource acquired and released by hand is not tracked by the effect system.")
  }
  test("message does not double a trailing period") {
    assertEquals(FindingDiagnostic(sample, pos, "already ends.").message,
      "already ends. A resource acquired and released by hand is not tracked by the effect system.")
  }
  test("categoryID is the kind, so scalafix prints [Rule.kind]") {
    assertEquals(FindingDiagnostic(sample, pos, "t").categoryID, "manual-resource")
  }
  test("every catalogued code is well-formed and unique") {
    val codes = FindingCatalog.all.map(_.code)
    assertEquals(codes, codes.distinct, "duplicate codes")
    codes.foreach(c => assert(c.matches("""^[A-Z][A-Za-z0-9]*\.[a-z0-9]+(-[a-z0-9]+)*$"""), s"malformed code $c"))
    assertEquals(codes, codes.sorted, "catalog must be sorted by code")
  }
  test("every field is non-empty, TAB-free, newline-free; title <= 60; explanation ends with a period") {
    FindingCatalog.all.foreach { f =>
      List(f.rule, f.kind, f.title, f.explanation, f.instruction, f.doc).foreach { v =>
        assert(v.nonEmpty && !v.contains('\t') && !v.contains('\n'), s"${f.code}: bad field '$v'")
      }
      assert(f.title.length <= 60, s"${f.code}: title too long")
      assert(f.explanation.endsWith("."), s"${f.code}: explanation must end with a period")
      assert(f.doc.startsWith("#"), s"${f.code}: doc must be an index.md anchor")
    }
  }
  test("byCode indexes every finding") {
    FindingCatalog.all.foreach(f => assertEquals(FindingCatalog.get(f.code), Some(f)))
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mill scalafix.test.testOnly fix.FindingCatalogSuite` → compile error (`Finding` not found).

- [ ] **Step 3: Implement**

`scalafix/src/fix/FindingCatalog.scala`:
```scala
package fix

import scalafix.lint.{Diagnostic, LintSeverity}
import scala.meta.inputs.Position

/** One finding KIND a rule can report. `code` (= `rule.kind`) is what scalafix prints as the lint id and what an
  * agent harness joins instructions on; `explanation` is appended to every message for humans; `instruction` is
  * the repair guidance published in `findings.tsv`. Codes are append-only (docs/MAINTENANCE.md).
  */
final case class Finding(rule: String, kind: String, title: String, explanation: String, instruction: String, doc: String) {
  def code: String = s"$rule.$kind"
}

object FindingCatalog {
  val all: List[Finding] = findings.Families.all.sortBy(_.code)
  val byCode: Map[String, Finding] = all.map(f => f.code -> f).toMap
  def get(code: String): Option[Finding] = byCode.get(code)

  /** `<text>. <explanation>` — the human reads the finding first; scalafix adds `[rule.kind]` itself. */
  def messageOf(finding: Finding, text: String): String = {
    val t = text.trim
    val sep = if (t.endsWith(".")) " " else ". "
    s"$t$sep${finding.explanation}"
  }
}

/** The one Diagnostic every purrism rule emits. `categoryID = kind` makes the lint id `Rule.kind`. */
final case class FindingDiagnostic(finding: Finding, override val position: Position, text: String) extends Diagnostic {
  override def message: String = FindingCatalog.messageOf(finding, text)
  override def categoryID: String = finding.kind
  override def severity: LintSeverity = LintSeverity.Warning
}
```

`scalafix/src/fix/findings/Families.scala`:
```scala
package fix.findings

import fix.Finding

/** Every family object lists its findings here; FindingCatalog reads this list. Task 2 fills it. */
object Families {
  val all: List[Finding] = List.empty
}
```

- [ ] **Step 4: Run to verify it passes** — the six tests pass (catalog empty is fine for now). Also `./mill scalafix.compile`.

- [ ] **Step 5: Commit**
```bash
git add scalafix/src/fix/FindingCatalog.scala scalafix/src/fix/findings/Families.scala scalafix/test/src/fix/FindingCatalogSuite.scala
git commit -m "findings: Finding, FindingCatalog and the one FindingDiagnostic every rule will emit

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 2: Catalogue every finding kind and migrate all lint sites

**Files:**
- Create: `scalafix/src/fix/findings/{IdiomFindings,ArrowFindings,CatsFunctionFindings,PolymorphicFindings,OpaqueFindings}.scala`
- Modify: `scalafix/src/fix/findings/Families.scala` (concatenate the five)
- Modify: `scalafix/src/fix/IdiomRules.scala`, `scalafix/src/fix/idioms/*.scala` (`IdiomFinding(tree, finding: Finding)`), `PreferArrow.scala`, `PreferCatsFunctions.scala`, `PreferPolymorphicCollections.scala`, `PreferPolymorphicTypeclasses.scala`, `PropagateOpaqueType.scala` — every `Patch.lint(...)` site (16 today) becomes `Patch.lint(FindingDiagnostic(<finding>, pos, <text>))`; the old `*Diagnostic` classes are deleted
- Modify: `scalafix/testInput/src/**` — every `// assert: <Rule>` marker on a linted line becomes `// assert: <Rule>.<kind>`
- Modify: `build.mill` — MiMa filters for the removed classes
- Test: existing `SemanticFixtureSuite` (testkit), `FindingCatalogSuite`

**Interfaces:**
- Consumes: `Finding`, `FindingDiagnostic`, `FindingCatalog.messageOf`.
- Produces: `findings.Families.all` non-empty; every rule's lints carry a code; the inventory of codes (list them in the report).

- [ ] **Step 1: Inventory the finding kinds (write the list into your report before coding)**

For each lint site, decide the kind from the message constant or reason it carries. Known today (from `scalafix/src/fix`): `IdiomRules` via `IdiomFinding(term, <Kind>)` in `idioms/EffectIdiomRules.scala` (`ManualResource`, `MutableReference`, `UnsafeCast`, …) and `idioms/OptionIdiomRules.scala` (`ThrowingLookup`, …) — one code per constant: `PreferEffectIdioms.manual-resource`, `PreferEffectIdioms.mutable-reference`, `PreferEffectIdioms.unsafe-cast`, `PreferOptionIdioms.throwing-lookup`, …; `PreferArrow`: `ArrowBudgetDiagnostic` (kind `readability-budget`), `FanOutShadowedInputDiagnostic` (`fan-out-shadowed-input`), plus the third `Patch.lint(` site (inspect its diagnostic and name it); `PreferCatsFunctions`: `PrivateCatsMatchDiagnostic` (`private-cats-match`), `MissingTypeclassEvidenceDiagnostic` (`missing-typeclass-evidence`), `AmbiguousCatsMatchDiagnostic` (`ambiguous-cats-match`); `PreferPolymorphicCollections`: `ContainerAbstractionDiagnostic` (inspect `message` variants → one kind per variant); `PreferPolymorphicTypeclasses`: `HKTDeclineDiagnostic` (`reason` variants → kinds); `PropagateOpaqueType`: `MergePointDiagnostic` (`merge-point`) and the second site. Read each rule's doc section in `docs/index.md` to write `title`/`explanation`/`instruction`/`doc` (the anchor of that section). Where a message today is composed from variables (e.g. a symbol name), keep that as `text` and make the *kind* the fixed part.

- [ ] **Step 2: Write the failing assertion — the coverage test skeleton lands in Task 3; here the failing check is the build:** change `IdiomFinding` to `final case class IdiomFinding(tree: Tree, finding: Finding)` and run `./mill scalafix.compile` → every `IdiomFinding(term, ManualResource)` site fails to compile. That is the RED signal for the migration.

- [ ] **Step 3: Write the family objects**

Pattern (`scalafix/src/fix/findings/IdiomFindings.scala`):
```scala
package fix.findings

import fix.Finding

object IdiomFindings {
  val ManualResource: Finding = Finding("PreferEffectIdioms", "manual-resource", "Manual acquire/release",
    "A resource acquired and released by hand is invisible to the effect system and leaks on failure.",
    "Wrap the acquire in Resource.make(acquire)(release) and consume it with use; re-run <module>.fix so the rewrite applies, then compile.",
    "#effect-boundaries")
  val MutableReference: Finding = Finding("PreferEffectIdioms", "mutable-reference", "Mutable reference in effectful code",
    "A var or mutable cell shared across effects has no ordering guarantees.",
    "Replace the var with Ref[F, A] created once in the resource scope and update it with Ref.update/modify.",
    "#effect-boundaries")
  // … one val per kind …
  val findings: List[Finding] = List(ManualResource, MutableReference /* , … */)
}
```
The other four families follow the same shape. `Families.all = IdiomFindings.findings ++ ArrowFindings.findings ++ CatsFunctionFindings.findings ++ PolymorphicFindings.findings ++ OpaqueFindings.findings`.

- [ ] **Step 4: Migrate the lint sites**

`IdiomRules.scala`: delete `IdiomDiagnostic`; `Patch.lint(FindingDiagnostic(finding.finding, finding.tree.pos, <the rule's short text>))` — the short text is what the old message constant said (keep the human wording; the explanation is now appended). `idioms/*.scala`: the constants `ManualResource` etc. become references to `IdiomFindings.ManualResource`. Each other file: replace its `*Diagnostic(pos, msg)` with `FindingDiagnostic(<FamilyObject>.<Kind>, pos, msg)` and delete the class. Grep afterwards: `grep -rn "extends Diagnostic" scalafix/src` must list only `FindingDiagnostic`.

- [ ] **Step 5: Update the fixture markers**

For every fixture line asserted with `// assert: <Rule>` that the migrated rule lints, change to `// assert: <Rule>.<kind>` (scalafix-testkit matches the full lint id `rule.categoryID`). Run `./mill scalafix.test` — testkit reports each mismatch with file:line; iterate until green. Keep `GoldenFixtureSuite` rewrites unchanged.

- [ ] **Step 6: MiMa**

Run `./mill scalafix.mimaReportBinaryIssues`. For each `MissingClassProblem`/`IncompatibleMethTypeProblem` on a removed `*Diagnostic` class, add to the `scalafix` object in `build.mill`:
```scala
  // Lint diagnostics are not API: the nine ad-hoc *Diagnostic classes were folded into FindingDiagnostic
  // (docs/superpowers/plans/2026-09-20-finding-catalog.md). Removing them is invisible to every consumer
  // that only runs the rules.
  def mimaBinaryIssueFilters = Seq(
    ProblemFilters.exclude[MissingClassProblem]("fix.IdiomDiagnostic"),
    // … one per removed class …
  )
```
(import `com.github.lolgab.mill.mima.*` where the module already imports MiMa). Re-run until clean.

- [ ] **Step 7: Verify** — `./mill scalafix.compile scalafix.test scalafix.mimaReportBinaryIssues` all green; `FindingCatalogSuite` still green with the real catalog (sorted, well-formed).

- [ ] **Step 8: Commit**
```bash
git add scalafix/src build.mill scalafix/testInput
git commit -m "findings: every rule lints through FindingDiagnostic with a catalogued code; fixtures assert Rule.kind

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 3: Coverage suite — every code asserted by a fixture, every asserted code catalogued

**Files:**
- Create: `scalafix/test/src/fix/FindingCoverageSuite.scala`

**Interfaces:**
- Consumes: `FindingCatalog.all`; the fixture tree `scalafix/testInput/src/**/*.scala` (path from the test's working directory: use `os.pwd` / the same root `SemanticFixtureSuite` uses — check `build.mill`'s `testkitProperties()` for the input path property and read it via `System.getProperty`).

- [ ] **Step 1: Write the test**

```scala
package fix

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

final class FindingCoverageSuite extends munit.FunSuite {
  private val inputRoot: Path = Paths.get(sys.props.getOrElse("purrism.testInput", "scalafix/testInput/src"))
  private val marker = """//\s*assert:\s*([A-Z][A-Za-z0-9]*\.[a-z0-9-]+)""".r

  private def assertedCodes: Map[String, List[String]] = // code -> files
    Files.walk(inputRoot).iterator().asScala.filter(_.toString.endsWith(".scala")).toList.flatMap { p =>
      marker.findAllMatchIn(Files.readString(p)).map(m => m.group(1) -> p.toString).toList
    }.groupMap(_._1)(_._2)

  test("every catalogued code is asserted by at least one fixture") {
    val asserted = assertedCodes.keySet
    val dead = FindingCatalog.all.map(_.code).filterNot(asserted)
    assert(dead.isEmpty, s"catalogued but never emitted in a fixture: ${dead.mkString(", ")}")
  }
  test("every asserted code is catalogued") {
    val unknown = assertedCodes.filterNot { case (c, _) => FindingCatalog.byCode.contains(c) }
    assert(unknown.isEmpty, s"fixtures assert codes the catalog does not know: ${unknown.mkString(", ")}")
  }
  test("no fixture still asserts a bare rule name on a purrism lint") {
    val bare = """//\s*assert:\s*([A-Z][A-Za-z0-9]*)\s*$""".r
    val rules = FindingCatalog.all.map(_.rule).toSet
    val hits = Files.walk(inputRoot).iterator().asScala.filter(_.toString.endsWith(".scala")).toList.flatMap { p =>
      Files.readAllLines(p).asScala.zipWithIndex.collect { case (l, i) if bare.findFirstMatchIn(l).exists(m => rules(m.group(1))) => s"$p:${i + 1}" }
    }
    assert(hits.isEmpty, s"bare-rule assertions on purrism lints (need Rule.kind): ${hits.mkString(", ")}")
  }
}
```
If `purrism.testInput` is not an existing property, add it to `testkitProperties()` in `build.mill` next to the testkit paths (value: the absolute `scalafix/testInput/src`).

- [ ] **Step 2: Run** — `./mill scalafix.test.testOnly fix.FindingCoverageSuite`. Expected first run: likely one or more `dead` codes (kinds that no fixture exercises). For each, add a minimal fixture under `scalafix/testInput/src/golden/<Rule><Kind>.scala` with the `/* rules = [...] */` header and an `// assert: Rule.kind` line (and the matching `testOutput` file if the rule rewrites — for lint-only, output equals input). Iterate to green.

- [ ] **Step 3: Full suite** — `./mill scalafix.test` green.

- [ ] **Step 4: Commit** `findings: coverage suite — every code has a fixture, every fixture code is catalogued` (+ Claude-Session line).

---

### Task 4: Generate and check `findings.tsv`

**Files:**
- Modify: `build.mill` (`scalafix` object): `findingsTsv` (Task), `findings()` (Command: write both copies), `findingsCheck()` (Command)
- Create (generated, committed): `docs/findings.tsv`, `scalafix/resources/purrism/findings.tsv`
- Test: `scalafix/test/src/fix/FindingsTsvSuite.scala`

**Interfaces:**
- Produces: `fix.FindingsTsv.render(version: String, findings: List[Finding]): String` (in `scalafix/src/fix/FindingsTsv.scala`) — header `# purrism <version>` then sorted rows `code\trule\ttitle\texplanation\tinstruction\tdoc`; `fix.FindingsTsv.parse(text): Either[String, (String, List[Finding])]` (version, findings) — the harness's format is exactly this.
- Mill: `./mill scalafix.findings` writes `docs/findings.tsv` and `scalafix/resources/purrism/findings.tsv`; `./mill scalafix.findingsCheck` exits non-zero when either committed file differs from the rendering.

- [ ] **Step 1: Tests**

```scala
package fix
final class FindingsTsvSuite extends munit.FunSuite {
  test("render/parse round-trip and header") {
    val text = FindingsTsv.render("0.9.1", FindingCatalog.all)
    assert(text.startsWith("# purrism 0.9.1\n"))
    assertEquals(FindingsTsv.parse(text), Right(("0.9.1", FindingCatalog.all)))
    assertEquals(text.linesIterator.drop(1).toList.map(_.split('\t').length).distinct, List(6))
  }
  test("the committed copies are the current rendering") {
    val version = sys.props.getOrElse("purrism.version", "dev")
    val expected = FindingsTsv.render(version, FindingCatalog.all)
    List("docs/findings.tsv", "scalafix/resources/purrism/findings.tsv").foreach { p =>
      assertEquals(os.read(os.pwd / os.RelPath(p)), expected, s"$p is stale — run ./mill scalafix.findings")
    }
  }
}
```
(`purrism.version` and the workspace `os.pwd` must be what the test sees — add `purrism.version` to `testkitProperties()` from `publishVersion()`; the second test may instead be implemented only as the Mill `findingsCheck` command if the test working directory is not the workspace — choose one and delete the other, say which.)

- [ ] **Step 2: Implement** `scalafix/src/fix/FindingsTsv.scala`:
```scala
package fix
object FindingsTsv {
  def render(version: String, findings: List[Finding]): String =
    (s"# purrism $version" :: findings.sortBy(_.code).map(f => List(f.code, f.rule, f.title, f.explanation, f.instruction, f.doc).mkString("\t"))).mkString("", "\n", "\n")
  def parse(text: String): Either[String, (String, List[Finding])] = {
    val lines = text.linesIterator.toList
    lines match {
      case h :: rows if h.startsWith("# purrism ") =>
        val parsed = rows.filter(_.nonEmpty).map { r =>
          r.split('\t', -1) match {
            case Array(code, rule, title, expl, instr, doc) if code == s"$rule.${code.stripPrefix(rule + ".")}" =>
              Right(Finding(rule, code.stripPrefix(rule + "."), title, expl, instr, doc))
            case other => Left(s"bad row (${other.length} fields): $r")
          }
        }
        parsed.collectFirst { case Left(e) => e }.toLeft((h.stripPrefix("# purrism "), parsed.collect { case Right(f) => f }))
      case _ => Left("missing '# purrism <version>' header")
    }
  }
}
```
`build.mill`, in `object scalafix`:
```scala
  /** The finding table (spec 2026-09-20-diagnostic-instruction-table-design §1): rendered from FindingCatalog. */
  def findingsTsv = Task {
    val out = Task.dest / "findings.tsv"
    val cp = runClasspath().map(_.path)
    // render via the compiled catalog: run fix.FindingsTsvMain <version> > out
    os.proc("java", "-cp", cp.mkString(java.io.File.pathSeparator), "fix.FindingsTsvMain", publishVersion()).call(stdout = out)
    PathRef(out)
  }
  def findings() = Task.Command {
    val src = findingsTsv().path
    os.copy.over(src, mill.api.BuildCtx.workspaceRoot / "docs" / "findings.tsv")
    os.copy.over(src, mill.api.BuildCtx.workspaceRoot / "scalafix" / "resources" / "purrism" / "findings.tsv")
  }
  def findingsCheck() = Task.Command {
    val generated = os.read(findingsTsv().path)
    Seq(os.rel / "docs" / "findings.tsv", os.rel / "scalafix" / "resources" / "purrism" / "findings.tsv").foreach { p =>
      val f = mill.api.BuildCtx.workspaceRoot / p
      require(os.exists(f) && os.read(f) == generated, s"$p is stale — run ./mill scalafix.findings")
    }
  }
```
with `scalafix/src/fix/FindingsTsvMain.scala`:
```scala
package fix
object FindingsTsvMain { def main(args: Array[String]): Unit = print(FindingsTsv.render(args.headOption.getOrElse("dev"), FindingCatalog.all)) }
```
(Follow the existing `catsIndex`/`catsIndexCheck` commands' style at `build.mill:224-246`; `publishVersion()` comes from `VcsVersionModule`.)

- [ ] **Step 3: Generate + verify** — `./mill scalafix.findings` then `./mill scalafix.findingsCheck` (green); edit one char in `docs/findings.tsv` → `findingsCheck` fails naming it; revert. `./mill scalafix.test` green (the resource is in the jar: `unzip -l out/scalafix/jar.dest/out.jar | grep findings.tsv` after `./mill scalafix.jar`).

- [ ] **Step 4: Commit** `findings: findings.tsv rendered from the catalog, checked in twice (docs, jar), findingsCheck guards staleness` (+ Claude-Session line).

---

### Task 5: Docs page, maintenance rule, CI

**Files:**
- Create: `docs/findings.md` (mdoc source rendering a table from `docs/findings.tsv`; follow how `docs/index.md` uses `mdoc:passthrough` and the `docs` module's helpers in `docs/src`)
- Modify: `docs/index.md` (link "Findings" near "Rule Sets"), `docs/MAINTENANCE.md` (codes are append-only; how to add a kind; run `./mill scalafix.findings`), `.github/workflows/ci.yml` and `release.yml` (add `./mill scalafix.findingsCheck` after `scalafix.test`), `README.md` (one sentence: lints print `[Rule.kind]`; the table is `docs/findings.tsv`)

- [ ] **Step 1: Docs page** — `docs/findings.md`:
````markdown
# Findings

Every purrism lint prints its code as the scalafix lint id — `[PreferEffectIdioms.manual-resource]` — and appends a
one-sentence explanation to the message. This table (generated from `FindingCatalog`, also shipped in the jar as
`purrism/findings.tsv`) adds the repair *instruction* an agent harness shows next to the finding.

```scala mdoc:passthrough
print(docs.Findings.renderTable(os.pwd / "docs" / "findings.tsv"))
```
````
and `docs/src/.../Findings.scala` in the `docs` module: read the TSV, skip the header, emit a markdown table `| code | title | explanation | instruction | doc |` with `doc` as a link to `index.md<anchor>`. `./mill docs.run` renders it; confirm `website/docs/findings.html` (or wherever `docs.run` writes) contains a known code.

- [ ] **Step 2: MAINTENANCE.md** — add a "Finding codes" section: codes `Rule.kind` are append-only; to add a kind: add a `Finding` to the family object, lint with `FindingDiagnostic`, add a fixture with `// assert: Rule.kind`, run `./mill scalafix.findings`, commit the two TSVs; renaming/removing a code is a breaking change for harnesses that vendor the table.

- [ ] **Step 3: CI** — in `ci.yml` after the `scalafix.test` step add `run: ./mill scalafix.findingsCheck`; same in `release.yml`'s verification step.

- [ ] **Step 4: Verify** — `./mill docs.run` green; `./mill scalafix.findingsCheck` green.

- [ ] **Step 5: Commit** `docs: Findings page from findings.tsv; append-only code rule; findingsCheck in CI` (+ Claude-Session line).

---

### Task 6: Local publish for the harness plan

**Files:** none new.

- [ ] **Step 1:** `./mill scalafix.publishLocal` and record the version it published (`./mill show scalafix.publishVersion`) in the report; `ls ~/.ivy2/local/io.github.mercurievv/scala-purrism-scalafix_3/` shows it.
- [ ] **Step 2:** Verify the jar carries the table: `unzip -p ~/.ivy2/local/io.github.mercurievv/scala-purrism-scalafix_3/<version>/jars/scala-purrism-scalafix_3.jar purrism/findings.tsv | head -3` → header + first rows.
- [ ] **Step 3:** Full CI set once more: `./mill scalafix.compile scalafix.test scalafix.findingsCheck docs.run scalafix.mimaReportBinaryIssues`. No commit unless something changed.

---

## Self-review against the spec

- §1 catalog fields/format, coded messages (via `categoryID` — the ruled deviation), coverage tests 1–3 → Tasks 1–3 (test 1 "every message coded" is now structural: only `FindingDiagnostic` extends `Diagnostic`, asserted by grep in Task 2 Step 4 and by the coverage suite's bare-rule check).
- §1 export (TSV header, columns, sort, jar + docs copies, `findingsCheck`), docs Findings page → Tasks 4–5.
- §3 versioning contract (header from build version), append-only rule, CI → Tasks 4–5.
- §4 purrism tests → Tasks 1, 3, 4; docs snapshot → Task 5 Step 1's grep.
- Names consistent: `Finding`, `FindingCatalog.{all,byCode,get,messageOf}`, `FindingDiagnostic`, `findings.Families.all`, `FindingsTsv.{render,parse}`, `FindingsTsvMain`, Mill `scalafix.{findingsTsv,findings,findingsCheck}`; code shape `Rule.kind` everywhere.
- The harness plan (next) consumes: TSV format from `FindingsTsv.render`, code shape `Rule.kind`, scalafix CLI line `path:line:col: warning: [Rule.kind] <text>. <explanation>`.

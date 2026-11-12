# TypeclassWeakening Generalisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TypeclassWeakening` replaces any over-strong context bound with the single weakest ancestor typeclass the body needs, reading library typeclass facts from TASTy on the classpath, with caching.

**Architecture:** The TASTy inspector moves from `scalafix/indexgen` into the published module and produces `CatsIndex` rows per jar, served bundled → disk cache → scan. A pure `BoundSolver` picks the target from requirements collected from SemanticDB (direct calls + evidence synthetics + local-helper fixpoint).

**Tech Stack:** Scala 3.8.4, Mill 1.1.x, scalafix 0.14.7, scala3-tasty-inspector 3.8.4, MUnit + munit-scalacheck, scalafix-testkit.

**Spec:** `docs/superpowers/specs/2026-11-12-typeclass-weakening-design.md`

## Global Constraints

- Branch: `fix/typeclass-weakening-weakest-target` (continues `f97b75a`).
- Commands prefixed `rtk`; build/test: `rtk mill scalafix.compile`, `rtk mill scalafix.test`; format: `mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll` (pre-commit hook enforces).
- Checked-in `scalafix/resources/cats-index/{typeclasses,capabilities,syntax,stdlib,gaps}.tsv` stay byte-identical (`mill scalafix.catsIndexCheck` passes). New tables are new files.
- Bundled index covers cats only; cats-effect comes from runtime scan (adding it to bundled tables would change `PreferPolymorphicTypeclasses` candidates).
- Exactly one bound emitted per original bound. Unknown ⇒ keep original.
- No `var`, `null`, `throw`, `asInstanceOf` in new code (Wartremover Unsafe); exceptions from TASTy/IO caught at the boundary into `Either[String, _]`.
- `findings.tsv` is append-only; new finding id `TypeclassWeakening.ambiguous-weakening`.
- Scala sources are read via ScalaSemantic MCP; edits via `annotated_source` write or IDE patch.
- Commits: no `Co-Authored-By`; end with `Claude-Session:` line.

## Review Focus

1. Bound whose TASTy is newer than the rule's compiler (user on Scala 3.9) — expect `Keep`, no crash; test in Task 3 (`scan of unreadable jar yields Left`).
2. Two scalafix processes writing the same cache key concurrently — expect one wins, neither reads a half-written dir; test in Task 3 (`concurrent writers leave a complete entry`).
3. Bound already minimal (second rule run) — expect no patch (idempotence); fixture `WeakenIdempotent` in Task 6.
4. Shadowed type parameter name (`class S[F[_]: Sync] { def h[F[_]: Sync] }`) — evidence of inner `F` must not count for outer; fixture case in Task 5.
5. Original import still used elsewhere in file (`Sync[F].delay` in another method) — import kept; fixture case in Task 6.

---

### Task 1: Shared TypeclassInspector in published module

**Files:**
- Create: `scalafix/src/fix/hkt/tasty/TypeclassInspector.scala`
- Modify: `scalafix/indexgen/src/fix/hkt/gen/CatsIndexGen.scala` (delegate to inspector; keep CLI + TSV writing)
- Modify: `build.mill` (`scalafix.mvnDeps` += `org.scala-lang:scala3-tasty-inspector_3:${Versions.scala3}`; drop it from `indexgen`)
- Test: `scalafix/test/src/fix/hkt/tasty/TypeclassInspectorSuite.scala`

**Interfaces:**
- Produces:
  ```scala
  final case class InspectedRows(
    typeclasses: List[List[String]],   // TypeclassHeader columns
    capabilities: List[List[String]],  // CapabilityHeader columns
    syntax: List[List[String]],        // SyntaxHeader columns
    evidence: List[List[String]],      // Task 2
    aliases: List[List[String]]        // Task 2
  )
  object TypeclassInspector {
    final case class Scope(typeclassPackages: String => Boolean, syntaxPackages: String => Boolean)
    val CatsScope: Scope   // exactly today's isTypeclass / "cats.syntax." filters
    val AnyScope: Scope    // all packages
    def inspectJar(jar: java.nio.file.Path, dependencies: List[java.nio.file.Path], scope: Scope): Either[String, InspectedRows]
  }
  ```
  Uses `TastyInspector.inspectAllTastyFiles(Nil, List(jar), dependencies)`; any throwable ⇒ `Left`.

- [ ] **Step 1: Failing test** — `inspectJar(catsCoreJar, deps, CatsScope)` rows contain `List("cats/Monad#", "cats/Applicative#,cats/FlatMap#", ...)` prefix and `inspectJar(catsEffectKernelJar, deps, AnyScope).typeclasses.map(_.head)` contains `"cats/effect/kernel/Sync#"`; jars found by filename in `System.getProperty("java.class.path")`.
- [ ] **Step 2:** `rtk mill scalafix.test.testOnly fix.hkt.tasty.TypeclassInspectorSuite` → FAIL (not found).
- [ ] **Step 3:** Move `CatsInspector` + helpers into `TypeclassInspector`, replacing the hard-coded package checks with `Scope`. `CatsIndexGen.main` calls `inspectJar(_, Nil, CatsScope)` and keeps its sorting/writing.
- [ ] **Step 4:** Suite PASS; `rtk mill scalafix.catsIndexCheck` PASS (bytes unchanged); `rtk mill scalafix.test` PASS.
- [ ] **Step 5:** Commit `refactor(index): share TASTy typeclass inspector with the rule module`.

### Task 2: Evidence and alias tables

**Files:**
- Modify: `scalafix/src/fix/hkt/tasty/TypeclassInspector.scala`
- Modify: `scalafix/src/fix/hkt/CatsIndex.scala` (optional tables; new accessors)
- Modify: `scalafix/indexgen/src/fix/hkt/gen/CatsIndexGen.scala` (write `evidence.tsv`, `aliases.tsv`)
- Create: `scalafix/resources/cats-index/evidence.tsv`, `scalafix/resources/cats-index/aliases.tsv` (via `mill scalafix.catsIndex`)
- Test: `scalafix/test/src/fix/hkt/CatsIndexSuite.scala`, `TypeclassInspectorSuite.scala`

**Interfaces:**
- Consumes: Task 1 `InspectedRows`.
- Produces on `CatsIndex`:
  ```scala
  def evidenceOf(method: Symbol): List[Symbol]          // typeclasses of implicit/using params
  def aliasesOf(typeclass: Symbol): List[TypeclassAlias]
  def aliasTarget(alias: Symbol): Option[TypeclassAlias]  // bound written as `MonadThrow`/`Temporal` → its typeclass
  final case class TypeclassAlias(alias: Symbol, renderName: String, importPath: String, target: Symbol, fixedArgs: List[String])
  def withTables(evidence: Iterator[String], aliases: Iterator[String]): Either[String, CatsIndex]
  ```
  Row formats: evidence `method\ttypeclasses(comma)`; aliases `alias\trenderName\timportPath\ttarget\tfixedArgs(comma, SemanticDB type symbols)`.

- [ ] **Step 1: Failing tests** — `CatsIndex.load().evidenceOf(Symbol("cats/Traverse#traverse()."))` contains `Symbol("cats/Applicative#")`; `aliasesOf(Symbol("cats/MonadError#"))` contains alias `cats/package.MonadThrow#` with `fixedArgs == List("java/lang/Throwable#")`; inspector on cats-effect-kernel yields alias `MonadCancelThrow` → `cats/effect/kernel/MonadCancel#`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Inspector: evidence = for every public method of indexed typeclasses and syntax classes, `evidenceTypeclasses` of its `given`/`implicit` params (existing helper). Aliases = `TypeDef` with one higher-kinded type param whose rhs dealiases to an applied typeclass with first arg = that param and remaining args concrete. `CatsIndex.load()` reads the two new resources when present.
- [ ] **Step 4:** Tests PASS; `rtk mill scalafix.catsIndexCheck` PASS (extend the check to the new files).
- [ ] **Step 5:** Commit `feat(index): method evidence and typeclass alias tables`.

### Task 3: Runtime index loader with caching

**Files:**
- Create: `scalafix/src/fix/hkt/tasty/JarLocator.scala`, `TastyScanCache.scala`, `RuntimeIndex.scala`
- Test: `scalafix/test/src/fix/hkt/tasty/TastyScanCacheSuite.scala`, `RuntimeIndexSuite.scala`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  ```scala
  object JarLocator { def jarOf(symbol: Symbol, classpath: List[Path]): Option[Path] }  // "a/b/C#" → jar containing a/b/C.tasty; entry listing memoised per jar
  final case class CacheConfig(dir: Option[Path], debug: Boolean)
  object CacheConfig { val default: CacheConfig /* ~/.cache/scala-purrism/tasty-index, debug=false */ }
  enum IndexSource { case Bundled, Disk, Scan }
  object TastyScanCache {
    def key(jar: Path): String                       // sha1(absPath|size|mtime|FormatVersion|scala.util.Properties.versionNumberString)
    def rows(jar: Path, deps: List[Path], config: CacheConfig): Either[String, (InspectedRows, IndexSource)]
  }
  object RuntimeIndex {
    def load(classpath: List[Path], seeds: Set[Symbol], config: CacheConfig): Either[String, CatsIndex]
  }
  ```
  `rows`: bundled when jar filename is `cats-core_3-<bundled catsVersion>.jar` / `cats-kernel_3-…` (bundled resources), else disk `<dir>/<key>/*.tsv`, else scan with `AnyScope` → write `<dir>/<key>.tmp-<uuid>` then `Files.move(ATOMIC_MOVE)`; move failure because target exists ⇒ delete temp, read target. `load`: closure over seeds' jars and their ancestors' jars (parents not yet present ⇒ locate ⇒ scan), merge rows, `CatsIndex.parse` + `withTables`; memoised in a `ConcurrentHashMap[List[Path] , CatsIndex]`. `debug` prints `TypeclassWeakening index: <jar> <source> <ms>ms` to stderr.

- [ ] **Step 1: Failing tests** (temp cache dir):
  - `cache miss scans then hits disk` — first `rows` source `Scan`, second `Disk`, rows equal.
  - `bundled cats jar never scans` — source `Bundled` for cats-core jar.
  - `scan of unreadable jar yields Left` — a zip containing a garbage `X.tasty`.
  - `concurrent writers leave a complete entry` — 8 parallel `rows` on an empty dir all `Right` with equal rows; dir has no `.tmp-` leftovers.
  - `load closes over ancestors` — seeds `{cats/effect/kernel/Sync#}` ⇒ index `isAncestor(cats/Monad#, cats/effect/kernel/Sync#)`.
  - `key changes with mtime` — touch copy of jar ⇒ different key.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement as above.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(index): load typeclass facts from classpath TASTy with disk cache`.

### Task 4: BoundSolver

**Files:**
- Create: `scalafix/src/fix/weakening/BoundSolver.scala`
- Test: `scalafix/test/src/fix/weakening/BoundSolverSuite.scala` (MUnit + ScalaCheck over `CatsIndex.load()` plus a hand-built index with a diamond)

**Interfaces:**
- Produces:
  ```scala
  enum Decision { case Keep; case Replace(target: Rendered); case Ambiguous(options: List[Rendered]) }
  final case class Rendered(symbol: Symbol, renderName: String, importPath: String)  // alias when binary
  object BoundSolver { def solve(bound: Symbol, required: Set[Symbol], index: CatsIndex): Decision }
  ```
  Algorithm per spec "Solving"; candidate ordering for `Ambiguous` by `(depth, symbol.value)`.

- [ ] **Step 1: Failing tests:**
  - `Sync`, `{Functor}` ⇒ `Replace(Functor)`; `{Functor, Semigroupal}` ⇒ `Replace(Apply)`; `{FlatMap}` ⇒ `Replace(FlatMap)`; `{FlatMap, Applicative}` ⇒ `Replace(Monad)`.
  - `Sync`, `{ApplicativeError}` ⇒ `Replace(t)` with `t.symbol == cats/ApplicativeError#` and `t.renderName == "ApplicativeThrow"` (binary target rendered through its alias).
  - `MonadError` with no matching alias for the fixed args ⇒ never a candidate.
  - `Traverse`, `{Functor, Foldable}` ⇒ `Keep`.
  - `Sync`, `{}` ⇒ `Keep`; `Sync`, `{Sync}` ⇒ `Keep`; `Functor`, `{Monad}` ⇒ `Keep`.
  - diamond index `D <: B, D <: C; B,C <: A`, bound `E <: B, E <: C`, `{A}` with B,C both minimal-renderable ⇒ `Ambiguous(List(B, C))`.
  - property: for random bound `b` from index and random subset `R` of `b`'s ancestors, `Replace(t)` ⇒ `t` is `b` or ancestor and covers `R`; and `solve(t, R) == Keep` (idempotence).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(weakening): solver for the single weakest covering typeclass`.

### Task 5: Requirement collection

**Files:**
- Create: `scalafix/src/fix/weakening/Requirements.scala` (generalises and replaces `scalafix/src/fix/WeakeningTarget.scala`, which is deleted)
- Test: via fixtures in Task 6 plus `scalafix/test/src/fix/weakening/RequirementsSuite.scala` for the pure helpers (`isMethod`, span keys)

**Interfaces:**
- Consumes: `CatsIndex` (Task 2 `evidenceOf`), Task 4 `Decision`.
- Produces:
  ```scala
  final case class BoundSite(owner: Tree, tparam: Type.Param, bound: Type, boundSymbol: Symbol)
  object Requirements {
    def sites(tree: Tree)(using SemanticDocument): List[BoundSite]                 // unary tparams, context bounds resolving to indexed typeclasses; alias bounds resolved via index.aliasTarget
    def collect(site: BoundSite, index: CatsIndex, helpers: Symbol => Option[Symbol])(using SemanticDocument): Option[Set[Symbol]]
  }
  ```
  `None` ⇒ unexplained evidence ⇒ Keep. Evidence identified by the evidence param's *symbol* (the tparam's context-bound evidence local, from synthetics' `IdTree` whose type arg symbol == `site.tparam.name.symbol`), not by display name (Review Focus 4). `helpers(methodSymbol)` returns the weakened bound of a local method (Task 6 fixpoint) or `None`. Conversion-on-same-span and `isMethod` rules from `WeakeningTarget` carry over; signature evidence for library methods via `index.evidenceOf`, for local via `symbol.info`.

- [ ] Steps 1–5 as TDD on `RequirementsSuite`; commit `feat(weakening): collect required typeclasses from SemanticDB`.

### Task 6: Rule wiring, config, lint, fixtures

**Files:**
- Modify: `scalafix/src/fix/TypelevelPurrism.scala` (`TypeclassWeakening` class gets config + `withConfiguration`; uses `Requirements` + `BoundSolver`; patches import of `Rendered.importPath`; removes original import when unused; lint on `Ambiguous`. Remove `MonadBoundCandidates`, `MonadOperations`, `StrongerThanMonadOperations`, `needsAtMostMonad` and helper-gate functions; `TypeclassWeakening.contextBoundWeakenings(...)` object methods kept `@deprecated` returning `Nil`)
- Create: `scalafix/src/fix/weakening/TypeclassWeakeningConfig.scala` (`cacheDir: String`, `debug: Boolean`, decoder; default per spec)
- Modify: `scalafix/resources/purrism/findings.tsv` (append `TypeclassWeakening.ambiguous-weakening`), finding catalog registration
- Modify: `scalafix/test/src/fix/GoldenFixtureSuite.scala` (delete the 6 syntactic weakening tests; replaced by fixtures)
- Create fixtures `scalafix/testInput/src/golden/` + `testOutput`: `WeakenToWeakestTarget.scala` (rewrite expectations: map→Functor, product+map→Apply, pure+map→Applicative, traverse→Applicative via evidence, flatMap→FlatMap, flatMap+pure→Monad, for-comprehension, local helper Applicative, class+helper fixpoint, shadowed F), `WeakenCatsEffect.scala` (handleError→ApplicativeThrow, raiseError+flatMap→MonadThrow, uncancelable→MonadCancelThrow, delay→kept, `F[_]: Temporal` using `sleep`→kept, `F[_]: MonadThrow` using only `map`→Functor), `WeakenKeep.scala` (Traverse map+foldLeft kept, unexplained external call kept, original import still used kept), `WeakenIdempotent.scala` (already-minimal bounds unchanged), `WeakenAmbiguous.scala` (user-defined diamond typeclasses ⇒ lint `// assert: TypeclassWeakening.ambiguous-weakening`)

**Interfaces:**
- Consumes: Tasks 3–5.
- Local-helper fixpoint: compute decisions for all `BoundSite`s of local methods first via memo `Map[Symbol, Decision]`; visiting a method already on the stack ⇒ its original bound.

- [ ] **Step 1:** Write all fixtures above (expected outputs must compile: `rtk mill scalafix.testOutput.compile`).
- [ ] **Step 2:** `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite` → new fixtures FAIL.
- [ ] **Step 3:** Implement wiring.
- [ ] **Step 4:** `rtk mill scalafix.test` PASS (all suites, incl. findings catalog/coverage).
- [ ] **Step 5:** Commit `feat(TypeclassWeakening): weaken any bound to the single weakest ancestor`.

### Task 7: Docs, goldens, live verification

**Files:**
- Modify: `mdoc-docs/src/main/scala/DocDiff.scala` (`--classpath` = `outDir` + `java.class.path` entries so the rule sees library TASTy)
- Modify: `docs/index.md` TypeclassWeakening section (behaviour, cats-effect examples, config, cache note)
- Modify: `scalafix/test/resources/golden/typelevel/expected/05-*.scala` (`Monad` → `FlatMap`), `07-*.scala` (class bound → `Monad` since it uses `flatMap` + `pure`, helper `normalize` → `Functor`; confirm by running the rule on the base file), `docs/findings.tsv` (`mill` findings task)
- Note: `Local Environment Test Repositories.md` repos for timing

- [ ] **Step 1:** Update goldens by running `TypeclassWeakening` on each base file via scalafix CLI and reviewing the diff by hand.
- [ ] **Step 2:** `rtk mill docs.run` → SUCCESS; rendered `bump` shows `SyncFunctor`.
- [ ] **Step 3:** Run rule with `debug = true` twice on one live repo: first run reports `Scan` with ms, second `Disk`; record both numbers in the PR description.
- [ ] **Step 4:** `rtk mill scalafix.test` PASS.
- [ ] **Step 5:** Commit `docs(TypeclassWeakening): generalised weakening, cache, examples`.

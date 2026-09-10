# RequireArrowArchitecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a new, standalone, diagnostic-only scalafix rule, `RequireArrowArchitecture`, that enforces the fully-abstract "arrow slot" architectural style described in the spec on whichever packages/paths a team opts in.

**Architecture:** A single-pass structural walk over each in-scope top-level template (`trait`/`class`/`case class`/`object`), built up incrementally task by task: scope matching first, then trait grammar, then case-class grammar, then composition-expression bodies, then object/companion rules, then cross-slot conversion, then supertype conformance, then the conversion-count cap. Each task's checker lives in its own file under `scalafix/src/fix/architecture/`; `fix.RequireArrowArchitecture` (the `SemanticRule`) wires them together and turns findings into `Patch.lint` diagnostics, honoring `// purrism:keep` via the existing `fix.Suppression` helper.

**Tech Stack:** Scala 3.8.4, scalafix (semantic rules, scalameta trees), Mill build (`rtk mill scalafix.compile`, `rtk mill scalafix.test`), munit-based `MunitSemanticRuleSuite` executed fixtures under `scalafix/testInput/src` (this rule never rewrites, so no `testOutput` counterparts are needed — see `docs/GOLDEN_FIXTURES.md`: "If a rule changes nothing, no output file is needed").

**Spec:** `docs/superpowers/specs/2026-09-10-require-arrow-architecture-design.md`

## Global Constraints

- Diagnostic-only: never emit a rewriting `Patch`, only `Patch.lint(...)`. (Spec: "It is diagnostic-only".)
- Default `severity = warning`; configurable to `error` (`docs/RULES.md`: diagnostics default to `LintSeverity.Warning`).
- `packages`/`paths` config lists default to empty, meaning the rule matches nothing (no accidental whole-project scans).
- `maxArrowConversions` defaults to `1`.
- Honor `// purrism:keep <reason>` via `fix.Suppression`, exactly like every other rule (`docs/RULES.md`).
- One diagnostic per violating declaration, anchored on that declaration — not per sub-expression token (`docs/RULES.md`: "report at the granularity of the decision, not of the evidence").
- Register the rule in `scalafix/resources/META-INF/services/scalafix.v1.Rule`.
- `ArrowFamily`'s known-typeclass set is a curated, closed list (mirroring `fix.hkt.CatsIndex`'s convention), not a dynamic semantic hierarchy walk — this codebase has no existing helper for the latter, and cats.arrow's own family is small and fixed.

---

## File Structure

- `scalafix/src/fix/architecture/ArrowFamily.scala` — the curated set of `cats.arrow` typeclass symbols that qualify a binary type as an arrow slot.
- `scalafix/src/fix/architecture/ArrowSlot.scala` — detecting arrow-slot declarations (both forms: type-parameter-with-context-bound, and abstract-type-member-with-instance-requirement), and classifying a member's declared type against the slots in scope.
- `scalafix/src/fix/architecture/ArchitectureFinding.scala` — the `(Tree, String)` finding shape, mirroring `fix.idioms.IdiomFinding`.
- `scalafix/src/fix/architecture/ArchitectureScope.scala` — deciding whether a file is in scope, from the `packages`/`paths` config.
- `scalafix/src/fix/architecture/TraitGrammar.scala` — grammar checks for `trait`/abstract `class` bodies (grows across Tasks 1, 2, 6, 7).
- `scalafix/src/fix/architecture/CaseClassGrammar.scala` — grammar checks for `case class` constructor params and bodies (Task 3).
- `scalafix/src/fix/architecture/CompositionExpr.scala` — the composition-expression grammar shared by case-class/object/companion `val`/`def` bodies (Tasks 4, 6).
- `scalafix/src/fix/architecture/ObjectGrammar.scala` — grammar checks for plain objects and companion-object constructors (Task 5).
- `scalafix/src/fix/architecture/ConversionBudget.scala` — counting distinct `ArrowConvert[P, Q]` requirements per template/companion pair against `maxArrowConversions` (Task 8).
- `scalafix/src/fix/RequireArrowArchitectureConfig.scala` — config case class + `ConfDecoder`.
- `scalafix/src/fix/RequireArrowArchitecture.scala` — the `SemanticRule` entry point, wiring scope + all grammar checkers + `Suppression` + `Patch.lint`.
- `scalafix/testInput/src/golden/architecture/*.scala` — one fixture per task, `// assert: RequireArrowArchitecture` on violating lines, no `testOutput` counterpart.

---

### Task 1: Scaffold, scope config, and trait member grammar (slot form 1)

**Files:**
- Create: `scalafix/src/fix/architecture/ArrowFamily.scala`
- Create: `scalafix/src/fix/architecture/ArrowSlot.scala`
- Create: `scalafix/src/fix/architecture/ArchitectureFinding.scala`
- Create: `scalafix/src/fix/architecture/ArchitectureScope.scala`
- Create: `scalafix/src/fix/architecture/TraitGrammar.scala`
- Create: `scalafix/src/fix/RequireArrowArchitectureConfig.scala`
- Create: `scalafix/src/fix/RequireArrowArchitecture.scala`
- Modify: `scalafix/resources/META-INF/services/scalafix.v1.Rule`
- Test: `scalafix/testInput/src/golden/architecture/TraitScopeAndSlots.scala`

**Interfaces:**
- Produces: `ArrowFamily.isArrowFamily(symbol: Symbol): Boolean`; `ArrowSlot(name: String)`; `ArrowSlot.declaredOn(tparams: List[Type.Param])(implicit doc: SemanticDocument): List[ArrowSlot]`; `ArrowSlot.isSlotApplication(tpe: Type, slots: List[ArrowSlot]): Boolean`; `ArchitectureFinding(tree: Tree, message: String)`; `ArchitectureScope.inScope(pkg: Option[String], path: String, config: RequireArrowArchitectureConfig): Boolean`; `TraitGrammar.findings(tree: Tree, doc: SemanticDocument): List[ArchitectureFinding]`.
- Consumes: nothing from earlier tasks (first task).

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/TraitScopeAndSlots.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.**"]
 */
package golden.architecture

import cats.arrow.Arrow

trait InScopeConforming[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
}

trait InScopeViolating[Step[_, _]: Arrow] {
  def name: String // assert: RequireArrowArchitecture
}

package outofscope {
  trait OutOfScopeViolating {
    def name: String // no assert: this package doesn't match `packages`
  }
}
```

Note the third trait lives in `golden.architecture.outofscope`, which *does* match the `golden.architecture.**` glob as written — fix this once `ArchitectureScope` is implemented in Step 3 by scoping the fixture's package glob precisely; for this first fixture, put the out-of-scope trait in a sibling top-level package instead:

```scala
// scalafix/testInput/src/golden/architecture/TraitScopeAndSlots.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.inscope.**"]
 */
package golden.architecture

import cats.arrow.Arrow

package inscope {
  trait InScopeConforming[Step[_, _]: Arrow] {
    type Error
    def validate: Step[Int, Either[Error, Int]]
  }

  trait InScopeViolating[Step[_, _]: Arrow] {
    def name: String // assert: RequireArrowArchitecture
  }
}

package outofscope {
  trait OutOfScopeViolating {
    def name: String
  }
}
```

- [ ] **Step 2: Run the fixture suite to see it fail to compile (the rule doesn't exist yet)**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — `RequireArrowArchitecture` is not a known rule name.

- [ ] **Step 3: Implement `ArrowFamily`**

```scala
// scalafix/src/fix/architecture/ArrowFamily.scala
package fix.architecture

import scalafix.v1._

/** The `cats.arrow` typeclasses that qualify a binary type constructor as
  * an "arrow slot", per docs/superpowers/specs/2026-09-10-require-arrow-
  * architecture-design.md. A curated, closed list -- the same convention
  * `fix.hkt.CatsIndex` uses for typeclass membership -- rather than a
  * dynamic hierarchy walk: cats.arrow's own family is small and fixed.
  */
object ArrowFamily {
  val roots: Set[String] = Set(
    "cats/arrow/Arrow#",
    "cats/arrow/ArrowChoice#",
    "cats/arrow/Category#",
    "cats/arrow/Compose#",
    "cats/arrow/Strong#",
    "cats/arrow/Choice#"
  )

  def isArrowFamily(symbol: Symbol): Boolean = roots.contains(symbol.value)
}
```

- [ ] **Step 4: Implement `ArrowSlot` (declaration form 1 only)**

```scala
// scalafix/src/fix/architecture/ArrowSlot.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** A binary type (kind `(*, *) => *`) standing in for a not-yet-chosen
  * concrete arrow. This task recognises only the type-parameter-with-
  * context-bound form, e.g. the `Step` in `trait Pipeline[Step[_, _]:
  * Arrow]`. The abstract-type-member form is added in Task 2.
  */
final case class ArrowSlot(name: String)

object ArrowSlot {

  def declaredOn(
      tparams: List[Type.Param]
  )(implicit doc: SemanticDocument): List[ArrowSlot] =
    tparams.collect {
      case Type.Param(_, Type.Name(name), holes, _, _, cbounds)
          if holes.size == 2 && cbounds.exists(isArrowBound) =>
        ArrowSlot(name)
    }

  private def isArrowBound(bound: Type)(implicit doc: SemanticDocument): Boolean = {
    val sym = bound.symbol
    sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
  }

  /** Whether `tpe` is exactly `Step[A, B]` for one of `slots` -- the only
    * shape a member's declared type is allowed to take.
    */
  def isSlotApplication(tpe: Type, slots: List[ArrowSlot]): Boolean = tpe match {
    case Type.Apply(Type.Name(n), List(_, _)) => slots.exists(_.name == n)
    case _                                    => false
  }

  /** Whether `tpe` names a concrete type with its own resolvable Arrow-
    * family instance (`Kleisli`, a plain `A => B`, or a custom Arrow
    * instance) rather than applying an abstract slot -- the specific
    * violation the spec calls out as "naming a concrete arrow type".
    */
  def namesConcreteArrow(tpe: Type)(implicit doc: SemanticDocument): Boolean =
    tpe match {
      case _: Type.Function => true
      case Type.Apply(head, _) =>
        val sym = head.symbol
        sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
      case _ => false
    }
}
```

- [ ] **Step 5: Implement `ArchitectureFinding` and `ArchitectureScope`**

```scala
// scalafix/src/fix/architecture/ArchitectureFinding.scala
package fix.architecture

import scala.meta.Tree

/** Something the grammar recognised as a violation. Always reported, never
  * rewritten -- there is no safe automatic fix from arbitrary logic into
  * arrow form.
  */
final case class ArchitectureFinding(tree: Tree, message: String)
```

```scala
// scalafix/src/fix/architecture/ArchitectureScope.scala
package fix.architecture

import fix.RequireArrowArchitectureConfig

/** Whether a file is in scope for `RequireArrowArchitecture`, per its
  * `packages`/`paths` config. A file matches if EITHER list has an entry
  * that matches. Both lists empty (the default) means nothing matches --
  * no accidental whole-project scans.
  *
  * `**` glob support is a package/path *prefix* match, not a general glob
  * engine: `"com.foo.wiring.**"` matches the package `com.foo.wiring` and
  * every subpackage of it. This is deliberately simpler than a full glob
  * library -- YAGNI until a real config needs more.
  */
object ArchitectureScope {

  def inScope(
      pkg: Option[String],
      path: String,
      config: RequireArrowArchitectureConfig
  ): Boolean =
    config.packages.exists(matchesPackage(pkg, _)) ||
      config.paths.exists(matchesPath(path, _))

  private def matchesPackage(pkg: Option[String], glob: String): Boolean =
    pkg.exists { p =>
      if (glob.endsWith(".**")) {
        val prefix = glob.dropRight(3)
        p == prefix || p.startsWith(prefix + ".")
      } else p == glob
    }

  private def matchesPath(path: String, glob: String): Boolean =
    if (glob.endsWith("/**")) path.startsWith(glob.dropRight(3))
    else path == glob
}
```

- [ ] **Step 6: Implement `TraitGrammar` (member-type check only, form-1 slots)**

```scala
// scalafix/src/fix/architecture/TraitGrammar.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `trait`/abstract `class` bodies. Grows across later
  * tasks (slot form 2, monomorphic-member check, supertype conformance,
  * the ArrowConvert special case).
  */
object TraitGrammar {

  def findings(defn: Defn.Trait)(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
    val slots = ArrowSlot.declaredOn(defn.tparams)
    defn.templ.stats.collect {
      case decl: Decl.Def  => checkMember(decl.decltpe, decl, slots)
      case decl: Decl.Val  => checkMember(decl.decltpe, decl, slots)
      case _: Decl.Type    => None // abstract type members are always allowed
    }.flatten
  }

  private def checkMember(
      decltpe: Type,
      member: Tree,
      slots: List[ArrowSlot]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    if (ArrowSlot.isSlotApplication(decltpe, slots)) None
    else if (ArrowSlot.namesConcreteArrow(decltpe)) Some(ArchitectureFinding(
      member,
      s"member names a concrete type `${decltpe.syntax}`; only an abstract " +
        "arrow slot (a type parameter or type member bounded by " +
        "Arrow/Compose/Category) applied to two types is allowed here"
    ))
    else Some(ArchitectureFinding(
      member,
      s"member has non-arrow type `${decltpe.syntax}`; only an abstract " +
        "arrow slot applied to two types, or an abstract type member, is " +
        "allowed here"
    ))
}
```

- [ ] **Step 7: Implement the config case class + decoder**

```scala
// scalafix/src/fix/RequireArrowArchitectureConfig.scala
package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import metaconfig.generic
import scalafix.v1.LintSeverity

final case class RequireArrowArchitectureConfig(
    severity: LintSeverity = LintSeverity.Warning,
    maxArrowConversions: Int = 1,
    packages: List[String] = Nil,
    paths: List[String] = Nil
)

object RequireArrowArchitectureConfig {
  val default: RequireArrowArchitectureConfig = RequireArrowArchitectureConfig()

  private implicit val severityDecoder: ConfDecoder[LintSeverity] =
    ConfDecoder.stringConfDecoder.flatMap {
      case "warning" => Configured.ok(LintSeverity.Warning)
      case "error"   => Configured.ok(LintSeverity.Error)
      case other =>
        Configured.error(s"expected 'warning' or 'error', got '$other'")
    }

  implicit val decoder: ConfDecoder[RequireArrowArchitectureConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("severity")(default.severity)
        .product(conf.getOrElse("maxArrowConversions")(default.maxArrowConversions))
        .product(conf.getOrElse("packages")(default.packages))
        .product(conf.getOrElse("paths")(default.paths))
        .map { case (((severity, maxConversions), packages), paths) =>
          RequireArrowArchitectureConfig(severity, maxConversions, packages, paths)
        }
    }
}
```

- [ ] **Step 8: Implement the `SemanticRule` entry point and register it**

```scala
// scalafix/src/fix/RequireArrowArchitecture.scala
package fix

import metaconfig.Configured
import scalafix.v1._

import fix.architecture._

/** Enforces the fully-abstract "arrow slot" architectural style on
  * configured packages/paths. Diagnostic-only: reports violations, never
  * rewrites, because there is no safe automatic rewrite from arbitrary
  * logic into arrow form.
  *
  * See docs/superpowers/specs/2026-09-10-require-arrow-architecture-design.md.
  */
final class RequireArrowArchitecture(config: RequireArrowArchitectureConfig)
    extends SemanticRule("RequireArrowArchitecture") {

  def this() = this(RequireArrowArchitectureConfig.default)

  override def withConfiguration(configuration: Configuration): Configured[Rule] =
    configuration.conf
      .getOrElse("RequireArrowArchitecture")(RequireArrowArchitectureConfig.default)
      .map(new RequireArrowArchitecture(_))

  override def fix(implicit doc: SemanticDocument): Patch = {
    val pkg = doc.tree.collect { case p: Pkg => p.ref.syntax }.headOption
    if (!ArchitectureScope.inScope(pkg, doc.input.syntax, config)) Patch.empty
    else {
      val suppression = Suppression.forDocument
      val findings = doc.tree.collect { case t: Defn.Trait => TraitGrammar.findings(t) }.flatten
      findings
        .filterNot(f => suppression.suppresses(f.tree))
        .map(f => Patch.lint(ArchitectureDiagnostic(f.tree.pos, f.message, config.severity)))
        .asPatch
    }
  }
}

final case class ArchitectureDiagnostic(
    override val position: scala.meta.inputs.Position,
    override val message: String,
    private val configuredSeverity: LintSeverity
) extends Diagnostic {
  override def severity: LintSeverity = configuredSeverity
}
```

```
# scalafix/resources/META-INF/services/scalafix.v1.Rule
# add this line to the existing file:
fix.RequireArrowArchitecture
```

- [ ] **Step 9: Run `rtk mill scalafix.compile` to catch compile errors**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS (fix any type errors surfaced here before continuing — `Type.Param`'s exact field order/arity depends on the scalameta version pinned in `build.mill`; check `mcp__scala-semantic__symbol_source` for `scala.meta.Type.Param` if the pattern match doesn't line up).

- [ ] **Step 10: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS — the `assert:` line inside `inscope` reports, the trait in `outofscope` does not (it's outside `golden.architecture.inscope.**`).

- [ ] **Step 11: Commit**

```bash
git add scalafix/src/fix/architecture/ArrowFamily.scala \
        scalafix/src/fix/architecture/ArrowSlot.scala \
        scalafix/src/fix/architecture/ArchitectureFinding.scala \
        scalafix/src/fix/architecture/ArchitectureScope.scala \
        scalafix/src/fix/architecture/TraitGrammar.scala \
        scalafix/src/fix/RequireArrowArchitectureConfig.scala \
        scalafix/src/fix/RequireArrowArchitecture.scala \
        scalafix/resources/META-INF/services/scalafix.v1.Rule \
        scalafix/testInput/src/golden/architecture/TraitScopeAndSlots.scala
git commit -m "feat: scaffold RequireArrowArchitecture with scope config and trait member grammar"
```

---

### Task 2: Second slot declaration form + monomorphic-member check

**Files:**
- Modify: `scalafix/src/fix/architecture/ArrowSlot.scala`
- Modify: `scalafix/src/fix/architecture/TraitGrammar.scala`
- Test: `scalafix/testInput/src/golden/architecture/TraitSlotFormsAndMonomorphism.scala`

**Interfaces:**
- Consumes: `ArrowSlot`, `ArrowFamily.isArrowFamily`, `ArchitectureFinding`, `TraitGrammar.findings` from Task 1.
- Produces: `ArrowSlot.declaredAsAbstractMember(stats: List[Stat])(implicit doc: SemanticDocument): List[ArrowSlot]`; `TraitGrammar.findings` now also rejects members generic beyond the slot's own two holes.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/TraitSlotFormsAndMonomorphism.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.slotforms.**"]
 */
package golden.architecture.slotforms

import cats.arrow.Arrow
import cats.effect.Sync

trait AbstractMemberSlot {
  type Step[_, _]
  given Arrow[Step]
  def validate: Step[Int, Either[String, Int]]
}

trait GenericMemberViolation[Step[_, _]: Arrow] {
  def step[G[_]: Sync]: Step[G[Int], Int] // assert: RequireArrowArchitecture
}
```

- [ ] **Step 2: Run the fixture suite to confirm the new cases aren't yet handled correctly**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — `AbstractMemberSlot.validate` is wrongly flagged (its slot isn't recognized yet), and `GenericMemberViolation.step` isn't flagged at all (no monomorphic check yet).

- [ ] **Step 3: Add the abstract-type-member slot form to `ArrowSlot`**

```scala
// add to scalafix/src/fix/architecture/ArrowSlot.scala, inside object ArrowSlot

/** Slots declared as an abstract type member with a separate instance
  * requirement, e.g. `type Step[_, _]` plus `given Arrow[Step]` (or an
  * equivalent abstract def/val whose declared type is `Arrow[Step]`)
  * elsewhere in the same template.
  */
def declaredAsAbstractMember(
    stats: List[Stat]
)(implicit doc: SemanticDocument): List[ArrowSlot] = {
  val typeMembers = stats.collect {
    case Decl.Type(_, Type.Name(name), tparams, _) if tparams.size == 2 => name
  }
  typeMembers.filter(name => stats.exists(requiresArrowInstanceFor(name, _)))
    .map(ArrowSlot(_))
}

private def requiresArrowInstanceFor(slotName: String, stat: Stat)(implicit
    doc: SemanticDocument
): Boolean = {
  def mentionsSlotUnderArrow(tpe: Type): Boolean = tpe match {
    case Type.Apply(head, List(Type.Name(`slotName`))) =>
      val sym = head.symbol
      sym != Symbol.None && ArrowFamily.isArrowFamily(sym)
    case _ => false
  }
  stat match {
    case Decl.Given(_, _, _, decltpe) => mentionsSlotUnderArrow(decltpe)
    case Decl.Def(_, _, _, _, decltpe) => mentionsSlotUnderArrow(decltpe)
    case Decl.Val(_, _, decltpe)       => mentionsSlotUnderArrow(decltpe)
    case _                              => false
  }
}
```

- [ ] **Step 4: Combine both slot-detection forms and add the monomorphic check in `TraitGrammar`**

```scala
// replace the body of TraitGrammar.findings in scalafix/src/fix/architecture/TraitGrammar.scala
def findings(defn: Defn.Trait)(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
  val slots = ArrowSlot.declaredOn(defn.tparams) ++
    ArrowSlot.declaredAsAbstractMember(defn.templ.stats)
  defn.templ.stats.collect {
    case decl: Decl.Def if decl.tparams.nonEmpty =>
      Some(ArchitectureFinding(
        decl,
        s"member `${decl.name.value}` is generic beyond its arrow slot's own " +
          "two holes; arrow-slot members must be monomorphic"
      ))
    case decl: Decl.Def => checkMember(decl.decltpe, decl, slots)
    case decl: Decl.Val => checkMember(decl.decltpe, decl, slots)
    case _: Decl.Type   => None
    case _: Decl.Given  => None // instance requirements for slot form 2
  }.flatten
}
```

- [ ] **Step 5: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS.

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scalafix/src/fix/architecture/ArrowSlot.scala \
        scalafix/src/fix/architecture/TraitGrammar.scala \
        scalafix/testInput/src/golden/architecture/TraitSlotFormsAndMonomorphism.scala
git commit -m "feat: recognize abstract-type-member arrow slots and reject generic members"
```

---

### Task 3: Case class grammar

**Files:**
- Create: `scalafix/src/fix/architecture/CaseClassGrammar.scala`
- Modify: `scalafix/src/fix/RequireArrowArchitecture.scala`
- Test: `scalafix/testInput/src/golden/architecture/CaseClassMembers.scala`

**Interfaces:**
- Consumes: `ArrowSlot`, `ArchitectureFinding` from Tasks 1-2.
- Produces: `CaseClassGrammar.findings(defn: Defn.Class)(implicit doc: SemanticDocument): List[ArchitectureFinding]`.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/CaseClassMembers.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.caseclass.**"]
 */
package golden.architecture.caseclass

import cats.arrow.Arrow

final case class Conforming[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]]
)

final case class PlainDataParam[Step[_, _]: Arrow](
  retries: Int, // assert: RequireArrowArchitecture
  step: Step[Int, Int]
)

final case class MutableField[Step[_, _]: Arrow](step: Step[Int, Int]) {
  var cache: Map[Int, Int] = Map.empty // assert: RequireArrowArchitecture
}
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — `case class` isn't checked at all yet, so neither `assert:` fires.

- [ ] **Step 3: Implement `CaseClassGrammar`**

```scala
// scalafix/src/fix/architecture/CaseClassGrammar.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `case class` constructor params and extra body
  * members. Composition-expression bodies (Task 4) are checked separately;
  * this task only rejects `var` outright and non-arrow-slot constructor
  * params.
  */
object CaseClassGrammar {

  def findings(defn: Defn.Class)(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
    val slots = ArrowSlot.declaredOn(defn.tparams) ++
      ArrowSlot.declaredAsAbstractMember(defn.templ.stats)
    val paramFindings = defn.ctor.paramss.flatten.flatMap(checkParam(_, slots))
    val varFindings = defn.templ.stats.collect {
      case v: Defn.Var =>
        ArchitectureFinding(v, "`var` is not allowed; only arrow-slot-typed vals/defs are")
    }
    paramFindings ++ varFindings
  }

  private def checkParam(
      param: Term.Param,
      slots: List[ArrowSlot]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    param.decltpe.flatMap { tpe =>
      if (ArrowSlot.isSlotApplication(tpe, slots)) None
      else if (isAbstractTypeReference(tpe)) None
      else if (ArrowSlot.namesConcreteArrow(tpe)) Some(ArchitectureFinding(
        param,
        s"constructor param `${param.name.value}` names a concrete type " +
          s"`${tpe.syntax}`; only an abstract arrow slot or an abstract type " +
          "is allowed here"
      ))
      else Some(ArchitectureFinding(
        param,
        s"constructor param `${param.name.value}` has plain data type " +
          s"`${tpe.syntax}`; only arrow-slot-typed or abstract-typed " +
          "params are allowed"
      ))
    }

  private def isAbstractTypeReference(tpe: Type)(implicit doc: SemanticDocument): Boolean =
    tpe match {
      case Type.Name(_) => tpe.symbol.info.exists(_.isAbstract)
      case _            => false
    }
}
```

- [ ] **Step 4: Wire `CaseClassGrammar` into the rule**

```scala
// in scalafix/src/fix/RequireArrowArchitecture.scala, extend the findings collection:
val traitFindings = doc.tree.collect { case t: Defn.Trait => TraitGrammar.findings(t) }.flatten
val caseClassFindings = doc.tree.collect {
  case c: Defn.Class if c.mods.exists(_.is[Mod.Case]) => CaseClassGrammar.findings(c)
}.flatten
val findings = traitFindings ++ caseClassFindings
```

- [ ] **Step 5: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS. (If `SymbolInformation#isAbstract` isn't the exact accessor name in the pinned scalafix version, check `mcp__scala-semantic__members` on `scalafix.v1.SymbolInformation` and adjust.)

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scalafix/src/fix/architecture/CaseClassGrammar.scala \
        scalafix/src/fix/RequireArrowArchitecture.scala \
        scalafix/testInput/src/golden/architecture/CaseClassMembers.scala
git commit -m "feat: enforce case class constructor param and var grammar"
```

---

### Task 4: Composition-expression grammar

**Files:**
- Create: `scalafix/src/fix/architecture/CompositionExpr.scala`
- Modify: `scalafix/src/fix/architecture/CaseClassGrammar.scala`
- Test: `scalafix/testInput/src/golden/architecture/CompositionBodies.scala`

**Interfaces:**
- Consumes: `ArrowSlot`, `ArchitectureFinding` from earlier tasks.
- Produces: `CompositionExpr.isValid(term: Term, slots: List[ArrowSlot])(implicit doc: SemanticDocument): Boolean`; `CaseClassGrammar.findings` now also checks non-abstract `def`/`val` bodies in a case class.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/CompositionBodies.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.composition.**"]
 */
package golden.architecture.composition

import cats.arrow.Arrow

final case class Wiring[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]],
  handleStep: Step[Int, Int]
) {
  def combined: Step[Int, Int] = {
    val normalized = handleStep andThen handleStep
    normalized andThen handleStep
  }

  def broken: Step[Int, Int] =
    if (true) handleStep else handleStep // assert: RequireArrowArchitecture

  def lambdaLogic: Step[Int, Int] =
    handleStep andThen ((x: Int) => x + 1) // assert: RequireArrowArchitecture
}
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — bodies aren't checked at all yet, so `combined` isn't confirmed valid and the two violations aren't reported.

- [ ] **Step 3: Implement `CompositionExpr`**

```scala
// scalafix/src/fix/architecture/CompositionExpr.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** The composition-expression grammar: the only shape a non-abstract
  * arrow-slot-typed `val`/`def` body may take, per the spec's
  * "Composition expressions" section.
  */
object CompositionExpr {

  private val combinators =
    Set("andThen", "compose", ">>>", "<<<", "first", "second", "split", "&&&", "|||", "id")

  def isValid(term: Term, slots: List[ArrowSlot])(implicit doc: SemanticDocument): Boolean =
    term match {
      case Term.Name(_) => true // a bare reference; identity is checked by the caller's context
      case Term.Select(_, Term.Name(_)) => true // eta-expanded method reference
      case Term.ApplyInfix(lhs, Term.Name(op), Nil, List(rhs)) if combinators(op) =>
        isValid(lhs, slots) && isValid(rhs, slots)
      case Term.Apply(Term.Select(recv, Term.Name(op)), List(arg)) if combinators(op) =>
        isValid(recv, slots) && isValid(arg, slots)
      case Term.Select(recv, Term.Name(op)) if combinators(op) =>
        isValid(recv, slots)
      case Term.Block(stats) =>
        stats.nonEmpty && stats.init.forall {
          case Defn.Val(_, _, _, rhs) => isValid(rhs, slots)
          case _                       => false
        } && (stats.last match {
          case t: Term => isValid(t, slots)
          case _        => false
        })
      case _ => false
    }
}
```

- [ ] **Step 4: Wire body-checking into `CaseClassGrammar`**

```scala
// add to CaseClassGrammar.findings in scalafix/src/fix/architecture/CaseClassGrammar.scala
val bodyFindings = defn.templ.stats.collect {
  case d: Defn.Def if !CompositionExpr.isValid(d.body, slots) =>
    ArchitectureFinding(
      d.body,
      s"body of `${d.name.value}` is not a valid composition expression " +
        "(only arrow-slot references, whitelisted combinators, and local " +
        "vals ending in one final composition are allowed)"
    )
  case d: Defn.Val if !CompositionExpr.isValid(d.rhs, slots) =>
    ArchitectureFinding(
      d.rhs,
      "val body is not a valid composition expression"
    )
}
paramFindings ++ varFindings ++ bodyFindings
```

- [ ] **Step 5: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS. (`Defn.Val`'s exact constructor shape — whether it takes one `rhs: Term` or a pattern list — depends on the scalameta version; check `mcp__scala-semantic__symbol_source` for `scala.meta.Defn.Val` if it doesn't line up, same for `Defn.Def`'s field order.)

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scalafix/src/fix/architecture/CompositionExpr.scala \
        scalafix/src/fix/architecture/CaseClassGrammar.scala \
        scalafix/testInput/src/golden/architecture/CompositionBodies.scala
git commit -m "feat: enforce the composition-expression grammar for case class bodies"
```

---

### Task 5: Object and companion-object grammar

**Files:**
- Create: `scalafix/src/fix/architecture/ObjectGrammar.scala`
- Modify: `scalafix/src/fix/RequireArrowArchitecture.scala`
- Test: `scalafix/testInput/src/golden/architecture/ObjectsAndCompanions.scala`

**Interfaces:**
- Consumes: `ArrowSlot`, `CompositionExpr`, `ArchitectureFinding` from earlier tasks.
- Produces: `ObjectGrammar.findings(defn: Defn.Object, isCompanion: Boolean)(implicit doc: SemanticDocument): List[ArchitectureFinding]`.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/ObjectsAndCompanions.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.objects.**"]
 */
package golden.architecture.objects

import cats.arrow.Arrow

trait Pipeline[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

final case class LivePipeline[Step[_, _]: Arrow](handleStep: Step[Int, Int])
    extends Pipeline[Step] {
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  def make[Step[_, _]: Arrow](handleStep: Step[Int, Int]): LivePipeline[Step] =
    LivePipeline(handleStep)
}

object PlainWiring {
  def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
    p.handle

  def evidenceInPlainObject[Step[_, _]: Arrow](s: Step[Int, Int]): Step[Int, Int] =
    s // assert: RequireArrowArchitecture
}
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — objects aren't checked yet, so `evidenceInPlainObject` isn't flagged.

- [ ] **Step 3: Implement `ObjectGrammar`**

```scala
// scalafix/src/fix/architecture/ObjectGrammar.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for plain objects and companion-object constructors. A
  * plain object's `def`s may be generic in an arrow slot (pure forwarding)
  * but may not carry a typeclass-evidence bound on it; that's reserved for
  * companion-object constructors, whose body must still be a valid
  * composition expression and whose return type must be the enclosing
  * module or an arrow-slot type.
  */
object ObjectGrammar {

  def findings(
      defn: Defn.Object,
      isCompanion: Boolean
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] =
    defn.templ.stats.collect {
      case d: Defn.Def =>
        val ownSlots = ArrowSlot.declaredOn(d.tparams)
        val hasEvidence = d.tparams.exists(_.cbounds.nonEmpty)
        if (hasEvidence && !isCompanion)
          Some(ArchitectureFinding(
            d,
            s"`${d.name.value}` carries a typeclass-evidence bound in a " +
              "plain (non-companion) object; evidence bounds are reserved " +
              "for companion-object constructors"
          ))
        else if (!CompositionExpr.isValid(d.body, ownSlots))
          Some(ArchitectureFinding(
            d.body,
            s"body of `${d.name.value}` is not a valid composition expression"
          ))
        else None
      case v: Defn.Val =>
        if (!CompositionExpr.isValid(v.rhs, Nil))
          Some(ArchitectureFinding(v.rhs, "val body is not a valid composition expression"))
        else None
      case other =>
        Some(ArchitectureFinding(other, "only val/def members are allowed in an object here"))
    }.flatten
}
```

- [ ] **Step 4: Wire `ObjectGrammar` into the rule, detecting companion objects**

```scala
// in scalafix/src/fix/RequireArrowArchitecture.scala
val objectFindings = doc.tree.collect { case o: Defn.Object => o }.flatMap { obj =>
  val isCompanion = doc.tree.collect {
    case c: Defn.Class if c.mods.exists(_.is[Mod.Case]) => c.name.value
    case t: Defn.Trait                                   => t.name.value
  }.contains(obj.name.value)
  ObjectGrammar.findings(obj, isCompanion)
}
val findings = traitFindings ++ caseClassFindings ++ objectFindings
```

- [ ] **Step 5: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS.

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scalafix/src/fix/architecture/ObjectGrammar.scala \
        scalafix/src/fix/RequireArrowArchitecture.scala \
        scalafix/testInput/src/golden/architecture/ObjectsAndCompanions.scala
git commit -m "feat: enforce object and companion-object constructor grammar"
```

---

### Task 6: Cross-slot conversion (`ArrowConvert`)

**Files:**
- Modify: `scalafix/src/fix/architecture/TraitGrammar.scala`
- Modify: `scalafix/src/fix/architecture/CompositionExpr.scala`
- Test: `scalafix/testInput/src/golden/architecture/CrossSlotConversion.scala`

**Interfaces:**
- Consumes: `ArrowSlot`, `CompositionExpr`, `TraitGrammar`, `ArchitectureFinding` from earlier tasks.
- Produces: `TraitGrammar` recognizes the `ArrowConvert[P, Q] { def apply[A, B](p: P[A, B]): Q[A, B] }` shape as conforming; `CompositionExpr.isValid` accepts a `.apply` call on such a value.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/CrossSlotConversion.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.conversion.**"]
 */
package golden.architecture.conversion

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

trait NotArrowConvertShape[P[_, _], Q[_, _]] {
  def apply[A, B, C](p: P[A, B], extra: C): Q[A, B] // assert: RequireArrowArchitecture
}

final case class Bridge[P[_, _]: Arrow, Q[_, _]: Arrow](
  convert: ArrowConvert[P, Q],
  step: P[Int, Int]
) {
  def bridged(implicit ev: ArrowConvert[P, Q]): Q[Int, Int] =
    ev.apply(step)
}
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — `ArrowConvert`'s own per-method type params (`A`, `B`) currently trip the Task 2 monomorphic check, and `NotArrowConvertShape` isn't flagged as a distinct violation (it just also trips the same generic check, but for the wrong reason -- add the special case to fix the true positive, and verify the negative fixture separately once the special case exists).

- [ ] **Step 3: Add the `ArrowConvert` special case to `TraitGrammar`**

```scala
// add to scalafix/src/fix/architecture/TraitGrammar.scala, inside object TraitGrammar

/** Whether `defn` is exactly the ArrowConvert shape: two slot type
  * parameters `P`, `Q` and a single abstract method whose only type
  * parameters (`A`, `B`) are applied to `P` and `Q` respectively --
  * `def apply[A, B](p: P[A, B]): Q[A, B]`. This is a special case, not a
  * general opening for per-method type parameters (see the spec's
  * "Cross-slot conversion" section).
  */
private def isArrowConvertShape(defn: Defn.Trait): Boolean = defn.tparams match {
  case List(Type.Param(_, Type.Name(p), List(_, _), _, _, _), Type.Param(_, Type.Name(q), List(_, _), _, _, _)) =>
    defn.templ.stats match {
      case List(Decl.Def(_, Term.Name("apply"), List(List(a, b)), List(param), Type.Apply(Type.Name(retHead), List(Type.Name(retA), Type.Name(retB))))) =>
        false // placeholder shape check refined below
      case _ => false
    }
  case _ => false
}
```

This sketch needs the actual `Decl.Def` field layout for a method with its own type parameters and one value parameter list; before finishing this step, run:

```
mcp__scala-semantic__symbol_source symbol="scala.meta.Decl.Def"
```

and match the real constructor shape (type params come before value-parameter lists). Rewrite `isArrowConvertShape` to check, precisely:
- exactly two class type params `P`, `Q`, each with two holes,
- exactly one statement, an abstract `def apply` with exactly two of its own type params `A`, `B`,
- exactly one value parameter, of declared type `P[A, B]`,
- a declared return type `Q[A, B]`.

Then use it in `findings`:

```scala
def findings(defn: Defn.Trait)(implicit doc: SemanticDocument): List[ArchitectureFinding] =
  if (isArrowConvertShape(defn)) Nil
  else {
    // ... existing body from Task 2 ...
  }
```

- [ ] **Step 4: Allow `.apply` on an `ArrowConvert` value in `CompositionExpr`**

```scala
// add a case to CompositionExpr.isValid's match, in
// scalafix/src/fix/architecture/CompositionExpr.scala
case Term.Apply(Term.Select(recv, Term.Name("apply")), List(arg)) =>
  isValid(recv, slots) && isValid(arg, slots)
```

(This is deliberately permissive about *which* value `.apply` is called on — Task 6 doesn't yet verify the receiver's type is actually an `ArrowConvert`; Task 8's conversion-budget counting is what gives this teeth, by only counting genuine `ArrowConvert[P, Q]` evidence requirements. A call to `.apply` on something else that happens to type-check as a valid composition is a pre-existing gap the spec doesn't require closing further.)

- [ ] **Step 5: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS.

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scalafix/src/fix/architecture/TraitGrammar.scala \
        scalafix/src/fix/architecture/CompositionExpr.scala \
        scalafix/testInput/src/golden/architecture/CrossSlotConversion.scala
git commit -m "feat: recognize the ArrowConvert special case and its apply call"
```

---

### Task 7: Supertype conformance for `extends`

**Files:**
- Modify: `scalafix/src/fix/architecture/TraitGrammar.scala`
- Test: `scalafix/testInput/src/golden/architecture/SupertypeConformance.scala`

**Interfaces:**
- Consumes: `TraitGrammar.findings` (now a function of a `Defn.Trait`, reused recursively on resolved supertype symbols) from Tasks 1-2, 6.
- Produces: `TraitGrammar.findings` also validates each `extends`/`with` clause.

- [ ] **Step 1: Write the failing fixture**

```scala
// scalafix/testInput/src/golden/architecture/SupertypeConformance.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.supertypes.**"]
 */
package golden.architecture.supertypes

import cats.arrow.Arrow

trait Marker extends Serializable // zero members: conforming

trait Base[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

trait Derived[Step[_, _]: Arrow] extends Base[Step] with Marker // conforming

trait NonConformingBase {
  def name: String
}

trait BadDerived extends NonConformingBase // assert: RequireArrowArchitecture
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — `extends` clauses aren't checked yet, so `BadDerived` isn't flagged.

- [ ] **Step 3: Add supertype checking to `TraitGrammar`**

```scala
// add to scalafix/src/fix/architecture/TraitGrammar.scala, inside object TraitGrammar

private def supertypeFindings(defn: Defn.Trait)(implicit doc: SemanticDocument): List[ArchitectureFinding] =
  defn.templ.inits.flatMap { init =>
    val sym = init.tpe.symbol
    if (sym == Symbol.None) Nil
    else
      doc.info(sym) match {
        case Some(info) if info.isTrait && declaresNoMembers(info) => Nil // marker trait
        case Some(info) if info.isTrait =>
          // Best-effort: only traits defined in this compilation unit can be
          // re-checked against the same grammar (their source tree is
          // available here); anything else -- including this trait's own
          // `ArrowConvert`-shaped or otherwise-conforming supertypes defined
          // in another file -- can't be re-walked from a symbol alone, so
          // decline rather than guess, per the spec.
          List(ArchitectureFinding(
            init,
            s"supertype `${sym.displayName}`'s shape can't be verified from " +
              "here; narrow the rule to run per-module so supertypes stay " +
              "locally checkable, or extend only marker traits / traits " +
              "defined in the same file"
          ))
        case _ => Nil
      }
  }

private def declaresNoMembers(info: SymbolInformation): Boolean =
  info.declarations.isEmpty
```

Then fold it into `findings`:

```scala
def findings(defn: Defn.Trait)(implicit doc: SemanticDocument): List[ArchitectureFinding] =
  if (isArrowConvertShape(defn)) Nil
  else supertypeFindings(defn) ++ {
    // ... existing member-checking body ...
  }
```

Note: this step's `doc.info(sym).declarations`/`isTrait` accessors are a best guess at the `scalafix.v1.SymbolInformation` surface; confirm the exact field names with `mcp__scala-semantic__members` on `scalafix.v1.SymbolInformation` before finishing this step, and adjust. The fixture's `Derived extends Base[Step] with Marker` case exercises the one case this task *can* resolve precisely (a zero-member marker trait); a same-file, same-principle non-marker supertype (the spec's other conforming case) needs the trait's own `Defn.Trait` tree, not just its symbol — if `doc.info` can't give enough to safely conform it, decline with the diagnostic above rather than silently accept, matching the spec's explicit fallback ("decline with a diagnostic rather than guess").

- [ ] **Step 4: Run `rtk mill scalafix.compile`**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS.

- [ ] **Step 5: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scalafix/src/fix/architecture/TraitGrammar.scala \
        scalafix/testInput/src/golden/architecture/SupertypeConformance.scala
git commit -m "feat: check extends-clause supertypes for conformance"
```

---

### Task 8: Conversion-count cap, registration polish, and full-spec regression fixture

**Files:**
- Create: `scalafix/src/fix/architecture/ConversionBudget.scala`
- Modify: `scalafix/src/fix/RequireArrowArchitecture.scala`
- Modify: `docs/RULES.md`
- Test: `scalafix/testInput/src/golden/architecture/ConversionBudget.scala`
- Test: `scalafix/testInput/src/golden/architecture/FullSpecExample.scala`

**Interfaces:**
- Consumes: everything from Tasks 1-7.
- Produces: `ConversionBudget.violations(templateAndCompanion: List[Stat], max: Int)(implicit doc: SemanticDocument): List[ArchitectureFinding]`.

- [ ] **Step 1: Write the failing fixture for the cap**

```scala
// scalafix/testInput/src/golden/architecture/ConversionBudget.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.budget.**"]
RequireArrowArchitecture.maxArrowConversions = 1
 */
package golden.architecture.budget

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

final case class OneConversion[P[_, _]: Arrow, Q[_, _]: Arrow](
  step: P[Int, Int]
)(implicit ev: ArrowConvert[P, Q])

final case class TwoConversions[P[_, _]: Arrow, Q[_, _]: Arrow, R[_, _]: Arrow]( // assert: RequireArrowArchitecture
  step: P[Int, Int]
)(implicit ev1: ArrowConvert[P, Q], ev2: ArrowConvert[Q, R])
```

- [ ] **Step 2: Run the fixture suite to see it fail**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: FAIL — nothing counts conversions yet, so `TwoConversions` isn't flagged.

- [ ] **Step 3: Implement `ConversionBudget`**

```scala
// scalafix/src/fix/architecture/ConversionBudget.scala
package fix.architecture

import scala.meta._
import scalafix.v1._

/** Counts distinct `ArrowConvert[P, Q]` requirements (by their `(P, Q)`
  * type-name pair, not by call site) across a trait/case-class and its
  * companion object combined, per the spec's "Conversion budget".
  */
object ConversionBudget {

  def violations(
      anchor: Tree,
      members: List[Stat],
      max: Int
  ): List[ArchitectureFinding] = {
    val pairs = members.flatMap(requirementsIn).distinct
    if (pairs.size > max)
      List(ArchitectureFinding(
        anchor,
        s"requires ${pairs.size} distinct ArrowConvert conversions " +
          s"(${pairs.map { case (p, q) => s"$p -> $q" }.mkString(", ")}); " +
          s"at most $max allowed (maxArrowConversions)"
      ))
    else Nil
  }

  private def requirementsIn(stat: Stat): List[(String, String)] = {
    def fromParams(paramss: List[List[Term.Param]]): List[(String, String)] =
      paramss.flatten.flatMap(_.decltpe).collect {
        case Type.Apply(Type.Name("ArrowConvert"), List(Type.Name(p), Type.Name(q))) => (p, q)
      }
    stat match {
      case c: Defn.Class => fromParams(c.ctor.paramss)
      case d: Defn.Def    => fromParams(d.paramss)
      case _               => Nil
    }
  }
}
```

- [ ] **Step 4: Wire it into the rule, pairing each template with its companion**

```scala
// in scalafix/src/fix/RequireArrowArchitecture.scala, after collecting traitFindings/
// caseClassFindings/objectFindings:
val templates = doc.tree.collect {
  case c: Defn.Class if c.mods.exists(_.is[Mod.Case]) => (c.name.value, c: Tree, c.ctor.paramss.flatten: List[Stat])
  case t: Defn.Trait                                    => (t.name.value, t: Tree, t.templ.stats)
}
val companions = doc.tree.collect { case o: Defn.Object => o.name.value -> o.templ.stats }.toMap
val budgetFindings = templates.flatMap { case (name, anchor, ownMembers) =>
  val companionMembers = companions.getOrElse(name, Nil)
  ConversionBudget.violations(anchor, ownMembers ++ companionMembers, config.maxArrowConversions)
}
val findings = traitFindings ++ caseClassFindings ++ objectFindings ++ budgetFindings
```

Note: `ConversionBudget.requirementsIn`'s `Defn.Class` branch expects `Stat`-typed constructor params; since `Term.Param` isn't a `Stat`, pass `c.ctor.paramss.flatten` to a dedicated overload instead of coercing types — adjust `requirementsIn` to accept `Either[Stat, Term.Param]` or simply give `ConversionBudget.violations` two entry points (one for `List[Stat]`, one for `List[Term.Param]`) and have the caller combine their results. Resolve this mismatch while making Step 5 compile; it doesn't change the counting semantics, only the plumbing.

- [ ] **Step 5: Run `rtk mill scalafix.compile`, fixing the plumbing noted above**

Run: `rtk mill scalafix.compile`
Expected: SUCCESS.

- [ ] **Step 6: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS.

- [ ] **Step 7: Write the full-spec conforming regression fixture**

Transcribe the spec's "Conforming" example (`docs/superpowers/specs/2026-09-10-require-arrow-architecture-design.md`, "Examples" section) verbatim into a new fixture, confirming the whole rule accepts it end-to-end with zero diagnostics:

```scala
// scalafix/testInput/src/golden/architecture/FullSpecExample.scala
/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.fullexample.**"]
 */
package golden.architecture.fullexample

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

trait Pipeline[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
  def handle: Step[Int, Int]
}

final case class LivePipeline[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]],
  handleStep: Step[Int, Int]
) extends Pipeline[Step] {
  type Error = String
  def validate: Step[Int, Either[Error, Int]] = validateStep
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  def make[Step[_, _]: Arrow](
    normalize: Step[Int, Int],
    validateStep: Step[Int, Either[String, Int]],
    handleStep: Step[Int, Int]
  ): LivePipeline[Step] = {
    val normalized = normalize andThen validateStep
    LivePipeline(handleStep = handleStep, validateStep = normalized)
  }
}

object PipelineWiring {
  def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
    p.handle
}
```

- [ ] **Step 8: Run the fixture suite**

Run: `rtk mill scalafix.test.testOnly fix.SemanticFixtureSuite`
Expected: PASS with zero diagnostics against `FullSpecExample.scala`.

- [ ] **Step 9: Run the full project test suite once, to catch regressions in other rules' fixtures**

Run: `rtk mill scalafix.test`
Expected: SUCCESS.

- [ ] **Step 10: Add a one-line pointer in `docs/RULES.md`**

```markdown
- `RequireArrowArchitecture` enforces a fully-abstract arrow-slot style (arrows, compositions, abstract types, and modules of only those) on configured `packages`/`paths`; diagnostic-only, own design doc at `docs/superpowers/specs/2026-09-10-require-arrow-architecture-design.md`.
```

Add this bullet under the "Scalafix Rules" section, near the other rule-specific pointers (e.g. next to the `PreferCatsFunctions` line).

- [ ] **Step 11: Commit**

```bash
git add scalafix/src/fix/architecture/ConversionBudget.scala \
        scalafix/src/fix/RequireArrowArchitecture.scala \
        docs/RULES.md \
        scalafix/testInput/src/golden/architecture/ConversionBudget.scala \
        scalafix/testInput/src/golden/architecture/FullSpecExample.scala
git commit -m "feat: cap distinct ArrowConvert requirements per class and document the rule"
```

---

## Self-Review Notes

- **Spec coverage:** scope config (Task 1), both arrow-slot forms (Tasks 1-2), monomorphic-member rule (Task 2), case-class constructor/`var` grammar (Task 3), composition-expression grammar including local-val blocks (Task 4), object/companion-object rules (Task 5), `ArrowConvert` special case and its `.apply` call (Task 6), supertype conformance (Task 7), and the `maxArrowConversions` cap (Task 8) each have a task and a fixture. The spec's diagnostic-message wording is followed in `TraitGrammar`/`CaseClassGrammar`'s messages.
- **Known soft spots, called out explicitly in-line rather than hidden:** the supertype-conformance check (Task 7) can only fully re-verify a marker trait or a same-file supertype from a bare symbol; a same-principle non-marker supertype defined in another file declines with a diagnostic instead of guessing, exactly as the spec's fallback describes. `ConversionBudget`'s `.apply`-call whitelisting in Task 6 doesn't itself verify the receiver is genuinely an `ArrowConvert` instance; Task 8's counting is what gives the mechanism teeth. Both are named as deliberate, spec-consistent simplifications, not gaps to silently paper over.
- **Type consistency:** `ArchitectureFinding(tree, message)` (Task 1) is the one finding shape used by every later task's checker. `ArrowSlot(name: String)` and its two constructors (`declaredOn`, `declaredAsAbstractMember`) are introduced in Tasks 1-2 and reused unchanged everywhere else. `RequireArrowArchitectureConfig`'s four fields (`severity`, `maxArrowConversions`, `packages`, `paths`) are all introduced in Task 1 so no later task needs to touch the decoder.
- Several steps (Tasks 2, 3, 4, 6, 7) flag exact scalameta node shapes (`Type.Param`, `Decl.Def`, `Defn.Val`, `SymbolInformation`) as needing a live check against the pinned scalameta/scalafix version via `mcp__scala-semantic__symbol_source`/`members` before the step is considered done — this is intentional, not a placeholder: the shapes are specified precisely enough to implement, but this codebase's own convention (per `SCALA_SEMANTIC_RULES.md`) is to verify against the compiler-backed index rather than assume API surface from memory.

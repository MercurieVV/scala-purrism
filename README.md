# scala-purrism

Scalafix rules for refactoring Typelevel Scala code toward pure, polymorphic Cats
and Cats Effect style.

They weaken over-strong effect bounds, turn hand-rolled plumbing into `Kleisli`
and `Arrow` composition, replace explicit typeclass calls with Cats syntax, and
propagate `opaque type`s through a whole program. All of them are **semantic**
rules: they read SemanticDB, so the target must compile with `-Ysemanticdb`
before any rule can run.

- [Quickstart](#quickstart) — get the rules running under your build tool
- [Rules](#rules) — one section per rule, with its configuration
- [Publishing](#publishing)

## Quickstart

Latest release:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.5.0
```

The published rule artifact currently targets Scala 3 and Scalafix `0.14.7`.

### sbt

Add sbt-scalafix to `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
```

Add the rule dependency to `build.sbt`:

```scala
ThisBuild / scalafixDependencies +=
  "io.github.mercurievv" %% "scala-purrism-scalafix" % "0.5.0"

ThisBuild / scalacOptions += "-Ysemanticdb"
```

Run:

```bash
sbt scalafix
```

### Mill

Add Mill Scalafix support to `build.mill`:

```scala
//| mvnDeps:
//| - com.goyeau::mill-scalafix::0.6.0

import com.goyeau.mill.scalafix.ScalafixModule
import mill.*, scalalib.*

object app extends ScalaModule, ScalafixModule {
  def scalaVersion = "3.8.4"

  def scalacOptions = Seq("-Ysemanticdb")

  def scalafixIvyDeps = Seq(
    mvn"io.github.mercurievv::scala-purrism-scalafix:0.5.0"
  )
}
```

Run:

```bash
./mill app.fix
```

### Scala CLI

Add the external rule dependency as a Scala CLI directive:

```scala
//> using scalafix.dep io.github.mercurievv::scala-purrism-scalafix:0.5.0
```

For semantic rules, make sure Scala CLI emits SemanticDB:

```scala
//> using options "-Ysemanticdb"
```

Run:

```bash
scala-cli fix . --power
```

### Scalafix CLI

Needed for whole-program rules — [`PropagateOpaqueType`](#propagateopaquetype)
is driven this way, because `sbt scalafix` / `app.fix` run per module and cannot
see the whole value graph:

```bash
cs install scalafix   # once

scalafix \
  --rules TypeclassWeakening \
  --tool-classpath "$(cs fetch -p io.github.mercurievv::scala-purrism-scalafix:0.5.0)" \
  --semanticdb-targetroots target/scala-3.8.4/classes \
  --sourceroot . \
  $(git ls-files '*.scala' | sed 's/^/--files /')
```

The **SemanticDB target root** is the directory that *contains*
`META-INF/semanticdb` — usually the compiler's class output. Find it with:

```bash
find . -type d -name semanticdb -path '*META-INF*' -not -path '*/.bloop/*'
# ./target/scala-3.8.4/classes/META-INF/semanticdb  ->  root is target/scala-3.8.4/classes
```

### Selecting rules

Create `.scalafix.conf` in the project where you want to run the rules:

```hocon
rules = [
  TypeclassWeakening,
  PreferKleisli,
  PreferArrow,
  PreferCatsSyntax,
  SimplifyCatsExpressions,
  PropagateOpaqueType
]
```

Every rule except `PropagateOpaqueType` runs with no configuration — listing it
in `rules` is the whole setup. `PreferKleisli` and `PreferArrow` each have one
optional opt-in flag, described below.

### The two-stage pipeline

`PreferKleisli` and `PreferArrow` compose, but **not in a single scalafix run**.
`PreferArrow` reads the SemanticDB payload to decide what is a `Kleisli`; after
`PreferKleisli` re-shapes a signature, that payload describes the *old* one. So
a project-wide refactoring is three steps:

```bash
scalafix --rules PreferKleisli ...   # lift effectful defs to Kleisli
<recompile>                          # refresh SemanticDB for the new signatures
scalafix --rules PreferArrow ...     # compose the lifted Kleislis into arrows
```

Running both rules in one invocation is not wrong, it is just weaker:
`PreferArrow` will not see anything `PreferKleisli` lifted in that same pass.

`scripts/purrism-pipeline.sh` drives all three steps against a target project:

```bash
scripts/purrism-pipeline.sh ../my-project [rule-version]
```

It writes a default `.scalafix-pipeline.conf` (both opt-in flags on) if none
exists, recompiles between stages, and passes only the files the compiler
emitted a payload for — a `.scala` script that is not part of the build would
otherwise fail the whole run with "SemanticDB not found".

## Rules

| rule | what it does | configuration |
| --- | --- | --- |
| [`TypeclassWeakening`](#typeclassweakening) | weaken over-strong effect bounds | none |
| [`PreferKleisli`](#preferkleisli) | effectful functions → `Kleisli` | optional — `crossFile` |
| [`PreferArrow`](#preferarrow) | `Kleisli` bodies → point-free `>>>`, `.map`, `&&&` | optional — `aggressive` |
| [`PreferCatsSyntax`](#prefercatssyntax) | typeclass calls → Cats syntax | none |
| [`SimplifyCatsExpressions`](#simplifycatsexpressions) | collapse expressions into existing combinators | none |
| [`PropagateOpaqueType`](#propagateopaquetype) | propagate an `opaque type` through the program | required — seeds |

### TypeclassWeakening

Weakens overly restrictive effect bounds (e.g. `Sync[F]` → `Monad[F]`) when only
monadic operations are used.

**Configuration:** none. Add `TypeclassWeakening` to `rules`.

### PreferKleisli

Refactors effectful functions into `Kleisli` compositions — introduction of the
`Kleisli` wrapper, and the `.local` input-reshape split.

`def m[F[_]: Sync](a: A, b: B): F[R]` becomes
`def m[F[_]: Sync]: Kleisli[F, (A, B), R]`, and its call sites are re-split to
match. A parameter that is an *effect callback* — a function whose result
mentions the effect, `progress: String => F[Unit]` — is not data flowing through
the arrow, so it stays a leading parameter list rather than joining the tuple:
`m(progress)((a, b))`.

**Configuration:**

```hocon
PreferKleisli.crossFile = true    # default false
```

Scalafix rewrites one document at a time, so by default only defs whose callers
are guaranteed to be in the same file are lifted. `crossFile = true` reads the
SemanticDB payload for the whole project up front and decides once, for every
symbol, whether it may be lifted and how its arguments split — which is what
allows a **public** def to be re-shaped, with callers in other files following.
Turn it on for a whole-project run; leave it off for single-file work.

Three shapes are always refused, because a re-shape would outrun its call sites:
a def passed *unapplied* anywhere (`Kleisli` does not conform to a function
type), a def whose body calls another def being re-shaped (deferred to a later
run), and a def with a placeholder call site.

### PreferArrow

Prefers point-free `Arrow` composition over unpacking `Kleisli` with
`.run`/`.apply` and stitching the pieces back by hand. An arrow-IR compiler
parses the monadic body, normalizes it, gates on a readability budget, and
renders `>>>` (linear chains, any length), `.map` (map after run), and `&&&`
(fan-out, any arity).

It rewrites the interior of a `Kleisli { x => ... }` in place — leaving the
signature untouched — as well as lifting `def m(x: A): F[B]` to a `Kleisli`
return. Kleisli identity is resolved through type aliases (`-->`, `Flow`,
fully-qualified, inferred) via SemanticDB.

**Configuration:**

```hocon
PreferArrow.aggressive = true     # default false
```

By default the readability budget declines any rewrite whose point-free form
would read worse than the source — a declined site is a correct outcome, not a
failure. `aggressive = true` relaxes it: generators calling *plain* `F`-returning
methods are lifted in place into `Kleisli { x => ... }` so they can fan out with
`&&&`, and a `yield` that still needs the input keeps it via a leading
`Kleisli.ask`. Discard generators (`_ <- log(...)`) are kept out of the fan-out
and rendered as `*>` before the work or `.flatTap` after it, so their thrown-away
results cost no tupling. The result is provably equivalent but busier than the
source, which is why it is opt-in.

Pattern catalogue and the aggressive-mode rules:
[docs/ARROW_PATTERNS.md](docs/ARROW_PATTERNS.md).

### PreferCatsSyntax

Replaces direct Cats typeclass calls such as `Applicative[F].pure(a)`,
`MonadThrow[F].raiseError[A](e)`, `Functor[F].map(fa)(f)` and
`FlatMap[F].flatMap(fa)(f)` with Cats syntax.

**Configuration:** none. Add `PreferCatsSyntax` to `rules`.

### SimplifyCatsExpressions

Simplifies common Cats and FP expressions using existing combinators: `.void`,
`.as`, `*>`, narrow `.mapN`, `Option(value)`, `Either.cond`.

**Configuration:** none. Add `SimplifyCatsExpressions` to `rules`.

### PropagateOpaqueType

Replaces one value's type with an `opaque type` and follows that value wherever
it flows — parameters, fields, returns, container type arguments, `Kleisli`
input tuples — wrapping where it is created and unwrapping where it crosses into
an API you do not own. Targets are exact SemanticDB symbols rather than names,
so unrelated `String`s that merely share a name are untouched.

This is the one rule with required configuration, and the one rule that needs
the **whole program** at once — run it from the [Scalafix
CLI](#scalafix-cli).

#### Configuration

```hocon
rules = [ PropagateOpaqueType ]

PropagateOpaqueType.types = [
  {
    name = "BranchName"
    underlying = "scala/Predef.String#"
    definitionFile = "BusinessLogic.scala"
    seeds = [ "_empty_/TaskRun#branchName." ]
    widen = []
  }
]
```

| key | meaning |
| --- | --- |
| `name` | the `opaque type` to introduce |
| `underlying` | SemanticDB symbol of the underlying type (default `scala/Predef.String#`) |
| `definitionFile` | where the type + companion are written, relative to the sourceroot; empty means you declare it yourself |
| `seeds` | SemanticDB symbols to start from |
| `widen` | extra symbols to pull into the closure — see [merge points](#merge-points) |

`PropagateOpaqueType.debug = true` prints the symbols each file declares.

`seeds` are SemanticDB symbols, not names. Any of a case-class field's four
symbols works — getter, constructor, `apply` or `copy` — they are treated as
aliases.

#### Running it

**1. Compile the target with SemanticDB on.** Nothing works without a payload:

```bash
sbt compile        # scalacOptions += "-Ysemanticdb"
./mill app.compile # def scalacOptions = Seq("-Ysemanticdb")
scala-cli compile . # //> using options "-Ysemanticdb"
```

**2. Find the SemanticDB target root** — see [Scalafix CLI](#scalafix-cli).

**3. Get the seed symbols.** Run once with `debug = true` and no types, and copy
the symbol you want out of the output:

```hocon
rules = [ PropagateOpaqueType ]
PropagateOpaqueType.debug = true
PropagateOpaqueType.types = []
```

**4. Write the real `types` block, then run:**

```bash
scalafix \
  --rules PropagateOpaqueType \
  --tool-classpath "$(cs fetch -p io.github.mercurievv::scala-purrism-scalafix:0.5.0)" \
  --semanticdb-targetroots target/scala-3.8.4/classes \
  --sourceroot . \
  $(git ls-files '*.scala' | sed 's/^/--files /')
```

`--files` takes one path per flag; repeat it — the `git ls-files` expansion does
that for a whole repo. A file whose SemanticDB payload is out of date is
reported and left untouched rather than patched against a stale view of the
code, so **recompile between runs**.

Working on the rule itself instead of consuming the release? Drop
`--tool-classpath` and build from this repo with `./mill scalafix.assembly`, or
use the [explorer](#finding-seeds-automatically), which classloads the rule
directly.

#### Merge points

Propagation runs forward, from a value to where it flows. When a parameter also
receives a value the closure does not cover, converting it would silently retype
that other value too — so the rule stops there, unwraps with `.value` at the
call site, and tells you:

```text
Git.scala:122: warning: _empty_/Git#branchExistsOnOrigin().[1,1] also receives
_empty_/Git#ensureBranch().(branchName), which the closure does not cover;
keeping the underlying type and unwrapping at the call site.
Add "_empty_/Git#ensureBranch().(branchName)" to `widen` to convert it too.
```

Whether the two values are really the same concept is a domain question, so the
rule reports rather than guesses. Adding the named symbol to `widen` pulls it in
and propagation continues through it.

#### Auto-discovering and propagating in one pass

`PropagateOpaqueType.autoDiscover` runs the same ranking the explorer below
uses, in-process, and folds the top candidates straight into this rule's own
`types` — one Scalafix invocation both finds and rewrites, no separate
explorer run and no intermediate `.conf` file required:

```hocon
rules = [ PropagateOpaqueType ]

PropagateOpaqueType.types = [
  { name = "BranchName", seeds = [ "_empty_/TaskRun#branchName." ] }
]

PropagateOpaqueType.autoDiscover {
  enabled = true
  topN = 10
  basicTypes = [ "scala/Predef.String#", "scala/Int#" ]
}
```

| key | default | meaning |
| --- | --- | --- |
| `enabled` | `false` | turn discovery on |
| `topN` | `10` | how many discovered clusters to add |
| `basicTypes` | `String, Int, Long, Double, Boolean, UUID` | underlying types worth wrapping |
| `serialize` | `false` | see below — **not supported**, kept only to fail with a pointer to the explorer |

Discovered candidates are **additive** to any hand-written `types`, but a
hand-written spec always wins a conflict: a discovered candidate is dropped if
its name or any of its seeds is already claimed by a manual entry. Everything
that survives is applied together in this rule's one `fix()` pass, exactly as
several hand-written `types` entries are today — so two discovered clusters
that touch the same declarations merge (or conflict) the same way two
hand-written ones would.

That single-pass merge is also its limit: unlike the explorer's own
recompile-between-candidates loop, nothing here reruns the compiler between
clusters, so it cannot serialize overlapping edits across files the way
`ExploreOpaques --target` can. Setting `autoDiscover.serialize = true` makes
that explicit by failing configuration outright — this module deliberately
excludes `scalafix-cli`/`-interfaces` so the published rule jar never drags
the CLI in as a transitive `scalafixDependencies` entry, and that same
exclusion is what the serialized apply needs. Use [the explorer
below](#finding-seeds-automatically) instead when a codebase needs several
overlapping passes.

#### Finding seeds automatically

Hand-picking seeds does not scale to a whole codebase, so the explorer picks
them mechanically. It ranks every basic-typed value by **how many nodes its
value-flow closure covers** — the more of the program an opaque type would
protect, the higher it ranks — and emits the top N as a pasteable
`PropagateOpaqueType.types` block.

This is the same ranking `PropagateOpaqueType.autoDiscover` runs in-process
(above); reach for the driver below instead when you want to review the HOCON
before applying it, or need the recompile-between-candidates loop to land more
than one cluster per file in a run.

It is a `main` in this repo's `scalafix.explorer` module, not part of the
published artifact, so it runs from a checkout of *this* project, pointed at the
target:

```bash
git clone https://github.com/MercurieVV/scala-purrism.git
cd scala-purrism
```

**1. Compile the target with SemanticDB** — as above. The explorer reads the
payload; it never builds anything itself, and fails with an explicit message
rather than reporting zero candidates when the payload is missing.

**2. Rank candidates without touching the target** (`--dry-run`):

```bash
./mill scalafix.explorer.runMain fix.opaque.ExploreOpaques \
  --target /path/to/target-project \
  --out /tmp/opaque-candidates.conf \
  -n 10 \
  --dry-run
```

| flag | default |
| --- | --- |
| `--target` | required — the compiled codebase to explore |
| `--out` | `<target>/opaque-candidates.conf` |
| `-n`, `--top` | `10` clusters |
| `--basic-types` | `scala/Predef.String#,scala/Int#,scala/Long#,scala/Double#,scala/Boolean#,java/util/UUID#` |
| `--dry-run` | rank and write the config, but do not rewrite |

This prints the ranked table and the HOCON block, and writes it to `--out`.
Read it: names are derived mechanically (most frequent member name, capitalized)
and `definitionFile` is the nearest package object, else the file defining the
cluster's dominant owner. Both are first drafts meant for a human to correct.

**3. Apply** — either edit `/tmp/opaque-candidates.conf` and run the Scalafix
CLI as in [Running it](#running-it), or re-run without `--dry-run` to let the
driver apply every spec itself:

```bash
./mill scalafix.explorer.runMain fix.opaque.ExploreOpaques \
  --target /path/to/target-project -n 10
```

One spec failing is reported and the rest still run. Rewrites land in the
target's **working tree only** — the driver never runs a git command against it,
so reviewing and reverting is yours to do:

```bash
cd /path/to/target-project && git diff          # review
cd /path/to/target-project && git checkout -- . # discard
```

**4. Recompile the target and repeat.** Rewriting a file invalidates its
SemanticDB and the rule refuses to patch against a stale payload, so roughly the
first spec touching a file lands per run — this is a loop, not one shot.
Applying many opaque types at once can also leave the target not compiling; that
is expected of an exploratory run.

## Publishing

Release and Sonatype Central setup notes are in [docs/PUBLISHING.md](docs/PUBLISHING.md).

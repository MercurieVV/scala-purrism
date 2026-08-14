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
| [`SimplifyCatsExpressions`](#simplifycatsexpressions) | collapse expressions into existing Cats combinators | none |
| [`PreferEffectIdioms`](#the-idiom-rules) | catch-all nets → `NonFatal`, `fold(F.unit)` → `traverse_`; reports manual resources and `AtomicReference` | optional — `rewrite`, `resources`, `refs` |
| [`PreferOptionIdioms`](#the-idiom-rules) | `null`-guarded lookups → `Option(...).fold` | optional — `rewrite`, `mouse` |
| [`PreferIndexedMap`](#the-idiom-rules) | `xs.indices.map(i => xs(i))` → `zipWithIndex` / plain `map`; effectful `foldLeft` → `foldM` | optional — `rewrite` |
| [`PreferStateThreading`](#the-idiom-rules) | pair-threading `foldLeft` → `traverse` in `State` | optional — `rewrite`, `stateT` |
| [`SuspendSideEffects`](#suspendsideeffects) | reports effects a signature does not mention; rewrites `F.pure(<effect>)` to `F.delay(<effect>)` | optional — `rewrite`, `report`, `effects` |
| [`PreferContainerTypeclasses`](#prefercontainertypeclasses) | concrete collection parameters → `S[_]` with the weakest Cats constraint | optional — `widenPublic`, `maxConstraints`, `containers` |
| [`PreferElementTypeclasses`](#preferelementtypeclasses) | operations whose Cats form is spelled differently and derives from a typeclass on the *element* | optional — `widenPublic`, `containers` |
| [`PropagateOpaqueType`](#propagateopaquetype) | propagate an `opaque type` through the program | required — seeds |
| `PreferCatsFunctions` | match project bodies against the Cats source index, rewrite to the winning public function | none |
| `PreferHKTTypeclasses` | abstract concrete `F`-returning functions to Cats typeclass constraints | optional — `widenPublic` |
| `TypelevelPurrism` | umbrella rule: runs `TypeclassWeakening` + `PreferKleisli` + `PreferArrow` + `PreferCatsFunctions` + `PreferHKTTypeclasses` + `PreferCatsSyntax` + `SimplifyCatsExpressions` in one pass | optional — `PreferArrow.aggressive`, `PreferHKTTypeclasses.widenPublic` |

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

Every match resolves a symbol. A receiver spelled `Monad` that is not
`cats.Monad`, or a `map` that belongs to `List` rather than to Cats syntax, is
left alone, and an unresolved symbol means no rewrite at all.

**Configuration:** none. Add `PreferCatsSyntax` to `rules`.

### SimplifyCatsExpressions

Simplifies common Cats and FP expressions using existing combinators.

| written as | rewritten to |
| --- | --- |
| `fa.map(_ => ())`, `fa.as(())` | `fa.void` |
| `fa.map(_ => c)` | `fa.as(c)` |
| `fa.flatMap(a => f(a).pure[F])` | `fa.map(a => f(a))` |
| `fa.flatMap(_ => fb)` | `fa *> fb` |
| `fa.flatMap(a => fb.map(b => (a, b)))` | `(fa, fb).mapN((a, b) => (a, b))` |
| `fa.flatMap(identity)`, `fa.flatMap(a => a)` | `fa.flatten` |
| `fa.map(identity)`, `fa.map(a => a)` | `fa` |
| `fa.map(f).flatten` | `fa.flatMap(f)` |
| `xs.map(f).sequence` | `xs.traverse(f)` |
| `xs.map(f).sequence_` | `xs.traverse_(f)` |
| `if (c) fa else F.unit` | `fa.whenA(c)` |
| `if (c) F.unit else fa` | `fa.unlessA(c)` |
| `fa.flatMap(a => g(a).map(b => (a, b)))` | `fa.mproduct(g)` |
| `opt.fold(F.pure(d))(f)` | `opt.fold(d.pure[F])(f)` |
| `if (x == null) None else Some(x)` | `Option(x)` |
| `if (c) Right(r) else Left(l)` | `Either.cond(c, r, l)` |

The receiver has to be a Cats one: `List(1, 2).map(_ => 42)` keeps its `map`,
and `Some` / `None` / `Right` / `Left` are matched by symbol, so shadowed
constructors are left alone. `whenA` and `unlessA` return `F[Unit]`, so those
two only fire when the branch is already `F[Unit]`. Where an outer and an inner
expression both match, only the outer rewrite is emitted.

**Configuration:** none. Add `SimplifyCatsExpressions` to `rules`.

### The idiom rules

Four rules for shapes that recur in any Cats codebase. Each rewrites what is
decidable from the expression and *reports* what is not, because a rewrite that
needs a fact the expression does not carry is a rewrite that stops compiling.

| rule | rewrites | reports |
| --- | --- | --- |
| `PreferEffectIdioms` | `catch { case _: Throwable => … }` → `catch { case NonFatal(…) => … }`; `opt.fold(F.unit)(f)` → `opt.traverse_(f)`; `try body finally r.close()` → `Using.resource(r)(_ => body)` | a `finally` that closes *and* does something else; `AtomicReference` (it is a `Ref`, but only once every use is in `F`); `asInstanceOf` |
| `PreferOptionIdioms` | `val v = m.get(k); if (v eq null) d else f(v)` → `Option(m.get(k)).fold(d)(v => f(v))`; `opt.map(f).getOrElse(d)` → `opt.fold(d)(f)` | `getOrElse(k, throw …)` — a partial lookup in a total signature |
| `PreferIndexedMap` | `xs.indices.map(i => f(xs(i)))` → `xs.map(x => f(x))`, or `xs.zipWithIndex.map { case (x, i) => … }` when the index is still read; `xs.foldLeft(F.pure(z))((acc, x) => acc.flatMap(…))` → `xs.foldM(z)(…)`; `xs.map(f).sum` → `xs.foldMap(f)` | — |
| `PreferStateThreading` | `xs.foldLeft((s0, empty)) { case ((s, out), x) => (s1, out :+ b) }` → `xs.traverse(x => State((s: S) => (s1, b))).run(s0).value` | a `(S, A) => (S, B)` method (that is `State[S, B]`, and `Ref#modifyState` takes one); a self-recursive effect (that is `iterateUntilM`) |

`PreferIndexedMap` declines when the body subscripts by anything but the loop
variable — `xs(i - 1)` reads a neighbour, which `zipWithIndex` does not hand
over. `PreferStateThreading` declines when the collected half is read rather
than only appended to, and when the seed does not say what the state type is.
It also distinguishes a poll loop from a *retry*: a recursion that leaves an
error handler or counts a budget down is not a fold over a condition, and
`iterateUntilM` has nowhere to put the giving up.

`Using.resource` rather than cats-effect `Resource`, because the rewrite has to
preserve the type: `Resource.fromAutoCloseable(…).use(…)` yields an `F[A]`
where the `try` yielded an `A`, so it applies only once the body is already in
`F`. `.map(f).sum` → `.foldMap(f)` checks the receiver's *own* type — `Vector`
and `Set` resolve `map` to the same symbol, and Cats has no `Foldable[Set]`.

Every one of them takes `rewrite = false` to leave only the reports.

**Run them twice.** Each rule is idempotent on its own, but one rule's output is
another's input — `opt.map(f).getOrElse(F.unit)` becomes a `fold` on the first
pass and `traverse_` on the second — so a single run can leave work on the
table. A second run reaches the fixpoint.

**Opting out.** Some code is deliberately un-idiomatic: a realtime callback with
a zero-allocation contract, a fold measured in bytes per chunk. A
`// purrism:keep <reason>` comment suppresses every idiom rule for the
expression on that line, the one below it, and — when it sits on a definition —
that whole definition.

```scala
// purrism:keep per-chunk render path, measured in bytes
def mix(xs: Array[Float]): Unit = …
```

### PreferContainerTypeclasses

Widens a concrete collection in a signature to the weakest Cats typeclass the
body actually uses:

```scala
private def names(users: List[String]): List[String] =
  users.map(user => user.toUpperCase)

private def names[S[_]: Functor](users: S[String]): S[String] =
  users.map(user => user.toUpperCase)
```

The interesting question is when *not* to. A body that reaches an element by
position — `xs(i)`, `.indices`, `.head` — is not expressing `Foldable`, it is
expressing random access, and Cats has no typeclass for that; those report
rather than rewrite. So does a definition handed over as a value, because a
polymorphic method does not eta-expand to a monomorphic function type.

**Configuration:** `widenPublic` (default `false`), `maxConstraints` (default
`2`), `containers` (default `List`, `Seq`, `Vector`, `IndexedSeq`, `LazyList`).
`widenPublic` defaults off because a widened signature keeps every ordinary call
site compiling — `f(myList)` still infers `S = List` — but whether the
definition is *also* handed over somewhere is only answerable from the file
scalafix was given when the definition is private or local.

### SuspendSideEffects

`def write(line: String): Unit` says it computes nothing and returns nothing. If
its body prints, opens a file or reads the clock, the type is not describing the
method — the effect happens when the method is *called*, so nothing can sequence
it, retry it, or run it somewhere else.

The rule reports a method whose declared result is not an effect but whose body
touches the world:

```scala
def record(target: Path, line: String): Unit =
  Files.writeString(target, line)   // reported: this is F[Unit] under Sync
```

It does not rewrite that. Moving the method to `F[Unit]` changes its signature
and every call site with it, which `docs/RULES.md` requires to be decided once
for the project rather than per file.

One shape *is* rewritten, because there it is a defect rather than a preference:

```scala
def startedAt: F[Long] = Sync[F].pure(System.nanoTime())
def startedAt: F[Long] = Sync[F].delay(System.nanoTime())
```

`pure` takes its argument by value, so the clock is read once — while the `F` is
being built — and every subsequent run of that value replays the first number.
`delay` is what was meant. Only entry points that *have* a `delay` are rewritten:
`Applicative[F].pure` around an effect is the same defect, but `Applicative`
cannot suspend, so there is nothing to rewrite it to.

Nothing is reported inside `Sync[F].delay { … }`, `Sync[F].blocking { … }` or
`IO { … }` — those are the fix — nor for a method already returning `F[A]`,
`IO[A]`, `Resource[F, A]` or `Stream[F, A]`. `F` is recognised as an effect by
being a higher-kinded type parameter in scope, not by being in a name table, so
`def run[F[_]: Sync](…): F[Unit]` is honest whatever its body does.

**Where this report is wrong**: realtime audio callbacks, UI event handlers on
the EDT, and measured paths — there the effect runs on a thread that cannot run
an effect at all. Those carry `// purrism:keep <reason>`, which this rule
honours like every other.

**Configuration:** `rewrite` (default `true`), `report` (default `true`),
`effects` — the result-type heads treated as effects, for projects with their
own effect alias.

### PreferElementTypeclasses

`PreferContainerTypeclasses` only ever widens a signature; the body it leaves
alone. Some stdlib operations have no same-named Cats counterpart, and the Cats
form takes its meaning from a typeclass on the *element* rather than on the
container:

```scala
private def rendered(rows: List[String]): String =
  rows.mkString("[", ",", "]")

private def rendered[S[_]: Foldable](rows: S[String]): String =
  rows.mkString_("[", ",", "]")
```

This is a **separate rule because it can change what the program prints.**
`mkString` renders each element with `toString`; `mkString_` renders it with
`Show`, and the two agree only where someone made them agree. `sum` against
`combineAll` is the same story for `Numeric` against `Monoid`.

Where the element is the definition's own type parameter, the rule adds the
constraint:

```scala
private def joined[A, S[_]: Foldable](rows: S[A])(using Show[A]): String =
  rows.mkString_(", ")
```

Where the element is concrete, it fires only for types Cats ships the instance
for. A domain type declines: nothing says `Show[Reading]` exists, and assuming
it does produces a file that will not compile.

**Configuration:** `widenPublic` (default `false`), `maxConstraints`,
`containers` — the same set as `PreferContainerTypeclasses`.

The rules live in `scalafix/resources/cats-index/stdlib.tsv` as `kind=element`
rows, which carry two columns the other kinds do not — the Cats spelling to
rename the call to, and the typeclass the meaning comes from:

```
scala/collection/*#mkString().	element	cats/Foldable#foldLeft().	cats/Foldable#foldLeft().	mkString_	cats/Show#	…
```

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
uses, in-process, and folds every candidate over the size threshold straight
into this rule's own `types` — one Scalafix invocation both finds and rewrites,
no separate explorer run and no intermediate `.conf` file required:

```hocon
rules = [ PropagateOpaqueType ]

PropagateOpaqueType.types = [
  { name = "BranchName", seeds = [ "_empty_/TaskRun#branchName." ] }
]

PropagateOpaqueType.autoDiscover {
  enabled = true
  minClusterSize = 4
  basicTypes = [ "scala/Predef.String#", "scala/Int#" ]
}
```

| key | default | meaning |
| --- | --- | --- |
| `enabled` | `false` | turn discovery on |
| `minClusterSize` | `4` | keep a cluster whose value-flow closure covers at least this many **declarations** |
| `basicTypes` | `String, Int, Long, Double, Boolean, UUID` | underlying types worth wrapping |
| `serialize` | `false` | see below — **not supported**, kept only to fail with a pointer to the explorer |

`minClusterSize` is a threshold, not a cap: every cluster over it is emitted and
every smaller one is dropped, with no limit on how many survive. That is what
makes discovery converge — once the wide flows are opaque, a rerun finds only
sub-threshold clusters and does nothing, whereas a fixed "top N" always has N
more to hand back.

It counts **entities — distinct declarations the rewrite would retype — not
closure nodes.** A closure is a set of `(symbol, type position)` pairs, so one
declaration contributes as many nodes as the flow reaches positions inside it: a
`Map[String, List[String]]` field is three on its own. Counting nodes would let
a single deeply-nested signature clear the threshold without the value
travelling anywhere, which is exactly the cluster an opaque type buys nothing
for. The ranking prints both columns.

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
them mechanically. It ranks every basic-typed value by **how many declarations
its value-flow closure covers** — the more of the program an opaque type would
protect, the higher it ranks — and emits every cluster over `--min-cluster-size`
as a pasteable `PropagateOpaqueType.types` block.

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
  -m 4 \
  --dry-run
```

| flag | default |
| --- | --- |
| `--target` | required — the compiled codebase to explore |
| `--out` | `<target>/opaque-candidates.conf` |
| `-m`, `--min-cluster-size` | `4` declarations — a threshold, not a cap (see above) |
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
  --target /path/to/target-project -m 4
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

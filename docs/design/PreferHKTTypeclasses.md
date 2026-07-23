# PreferHKTTypeclasses — design

Design of record for [#33](https://github.com/MercurieVV/scala-purrism/issues/33)
("Prefer HKT typeclasses over concrete containers"), produced by
[#36](https://github.com/MercurieVV/scala-purrism/issues/36).

Every item below is a **decision**. Downstream subtasks (#37–#44) implement it and
do not re-open it. If an implementer finds a decision unworkable, the fix is to
amend this document first and say so on #33 — not to diverge locally.

Repo facts this design is pinned to (verified in-tree, do not re-derive):

| Fact | Value |
| --- | --- |
| Build | Mill, `build.mill` |
| Scala | `Versions.scala3 = "3.8.4"` |
| Cats | `Versions.catsCore = "2.13.0"` (`org.typelevel:cats-core_3:2.13.0`) |
| Cats Effect | `Versions.catsEffect = "3.7.0"` |
| Scalafix | `Versions.scalafix = "0.14.7"` (artifacts suffixed `_3.8.4`, not `_3`) |
| Rule shells | `scalafix/src/fix/<Rule>.scala` |
| Rule helpers | `scalafix/src/fix/<subpackage>/` (`arrow/`, `opaque/`) |
| Executed fixtures | `scalafix/testInput/src/golden/<Name>.scala` + `scalafix/testOutput/src/golden/<Name>.scala`, **flat names** |
| Rule registration | `scalafix/resources/META-INF/services/scalafix.v1.Rule` |
| Compiler flags | `-Ysemanticdb -Wunused:imports -Werror` on the `scalafix` module |

---

## 1. Index source

**Decision: option (c) — read TASTy from the published `cats-core_3:2.13.0` jar with
`scala3-tasty-inspector`.**

Rejected:

- **(a)/(d) SemanticDB over a Cats checkout** — needs a git clone of `v2.13.0`, an sbt
  cross-build with `-Ysemanticdb`, and several minutes of compile per regeneration.
  A cheap runner cannot execute that from written instructions alone.
- **(b) Scaladoc/API metadata** — HTML/JSON scraping; no reliable override chains, no
  kind information, no stable symbol strings.
- **Java reflection over the jar** — erased generic signatures. A higher-kinded
  parameter `F[_]` is indistinguishable from a proper `F`, and the kind shape is the
  single most load-bearing field in this index.

TASTy wins because the jar is *already on the build's classpath*, the inspector
resolves parents, declarations, override chains and type-parameter kinds exactly as
the compiler saw them, and regeneration is one `mill` invocation with no network
beyond the coursier fetch mill already performs.

**Verified, not assumed.** The route was smoke-tested against the real artifact
before this document was written:

```
parents=List(cats.Functor, cats.Invariant, java.io.Serializable, ...)
decls=List(map, imap, fmap, widen, lift, void, fproduct, fproductLeft)
typeParams=Some(List(type F))
apply-parents=List(cats.Apply, cats.ApplyArityFunctions, cats.InvariantSemigroupal,
                   cats.Semigroupal, cats.Functor, cats.Invariant, ...)
tastys=949
```

Two implementation notes that cost time if rediscovered:

1. `TastyInspector.inspectTastyFilesInJar` needs the target's **own dependencies** on
   the classpath. Reading `cats-core_3:2.13.0` fails with
   `undefined: new org.typelevel.scalaccompat.annotation.package.uncheckedVariance2`
   unless `org.typelevel::scalac-compat-annotation:0.1.4` is also a dependency.
2. The scalafix artifacts use a **full** Scala 3 version suffix (`scalafix-rules_3.8.4`),
   the tasty inspector uses the binary suffix (`scala3-tasty-inspector_3`).
3. The resolved `cats-core` coordinate exposes Cats Kernel as its public transitive
   API. Generation therefore inspects both the resolved `cats-core_3` jar and its
   same-version `cats-kernel_3` jar; both paths are selected in `build.mill` from
   `Versions.catsCore`. The generator contains no Cats version literal.
4. Mill 1.1.7 does not allow a command-local `Task.dest` value to be passed into
   another `Task.Command`. The two wrapper commands therefore launch
   `fix.hkt.gen.CatsIndexGen` from the resolved `indexgen.runClasspath` in a Java
   subprocess. The ordinary `indexgen.runMain` entry point remains runnable.
5. Simulacrum-compatible members such as `cats/Functor.Ops#map().` exist in the
   Cats classfiles and in downstream SemanticDB, but their synthetic `Ops` trees are
   not present in the inspected TASTy; asking `Symbol.requiredClass` for them makes
   the inspector report bad symbolic references. A narrow classfile-reflection pass
   therefore enumerates only those wrapper method names and syntax-module fields.
   Capability identity, hierarchy, kinds, signatures, bodies and override roots remain
   TASTy-derived.

### Entry point

A new module in `build.mill`, sibling to the existing `scalafix.explorer` (which is
likewise excluded from the published artifact because it drags in tooling deps):

```scala
object indexgen extends ScalaModule {
  def scalaVersion = Versions.scala3
  def moduleDeps = Seq(scalafix)
  def mvnDeps = Seq(
    mvn"org.scala-lang:scala3-tasty-inspector_3:${Versions.scala3}",
    mvn"org.typelevel::scalac-compat-annotation:0.1.4"
  )
}
```

Runnable entry points:

| Command | Effect |
| --- | --- |
| `rtk mill scalafix.indexgen.runMain fix.hkt.gen.CatsIndexGen` | regenerates the artifacts in place |
| `rtk mill scalafix.catsIndex` | `Task.Command` wrapper for the above — **the documented command** |
| `rtk mill scalafix.catsIndexCheck` | regenerates into `Task.dest` and fails on any byte difference from the checked-in files |

The Cats coordinate is read from `Versions.catsCore`, never from a literal in the
generator. `catsIndex` runs **on demand** (Cats bumps are rare and arrive via
scala-steward); `catsIndexCheck` runs **in CI** and in `prePush`, so a hand-edited or
stale artifact fails loudly. Generator source lives at
`scalafix/indexgen/src/fix/hkt/gen/CatsIndexGen.scala`.

### Artifacts

Checked in under `scalafix/resources/cats-index/` (a new subdirectory; `scalafix/resources/`
currently holds only `META-INF/`), so they are on the published rule's classpath and
`CatsIndex.load()` reads them as ordinary resources.

| File | Columns |
| --- | --- |
| `typeclasses.tsv` | `symbol`, `parents`, `kind`, `typeParams`, `depth`, `renderName`, `importPath`, `public` |
| `capabilities.tsv` | `typeclass`, `method`, `owner`, `kind`, `derived`, `arity` |
| `syntax.tsv` | `syntaxMethod`, `owner`, `method`, `importPath` |
| `stdlib.tsv` | `concreteMethod`, `owner`, `method`, `note` |
| `gaps.tsv` | `typeclass`, `reason`, `tracked` |

Snapshot for Cats 2.13.0 on Scala 3.8.4 (data rows exclude headers):

| File | Data rows | Bytes |
| --- | ---: | ---: |
| `typeclasses.tsv` | 59 | 5,337 |
| `capabilities.tsv` | 1,471 | 130,439 |
| `syntax.tsv` | 354 | 42,998 |
| `stdlib.tsv` | 0 | 159 |
| `gaps.tsv` | 3 | 411 |
| **Total** | **1,887** | **179,344** |

TSV, not JSON: the rows are flat and uniform, a TSV diff is reviewable line-by-line in
a PR, and parsing needs no dependency. `symbol`/`method`/`owner` are **SemanticDB symbol
strings** (`cats/Functor#`, `cats/Functor#map().`) so they compare directly against
`scalafix.v1.Symbol` values obtained from a `SemanticDocument`. Multi-valued cells
(`parents`) are `,`-separated, themselves sorted by the same rule.

`stdlib.tsv` is generator-owned like the other four files. Its schema reserves the map
from concrete stdlib/Cats-data methods (`scala/collection/immutable/List#map().`) to the
capability they demand; the Cats 2.13.0 snapshot has no such rows yet. Policy-defined
`gaps.tsv` rows are emitted by the same generator so regeneration and drift checking
cover the complete artifact set.

### Stable sort — exact specification

Every generated file is written as:

1. one header line, `#` followed by the column names, tab-separated;
2. one deterministic `# generated by CatsIndexGen` source line stating that the file
   must not be edited by hand, the `org.typelevel::cats-core` and Scala versions passed
   from `Versions.catsCore` / `Versions.scala3`, and the TASTy extraction route;
3. data rows sorted **ascending** by column 1, ties broken by column 2, then column 3,
   … through the last column;
4. comparison is `java.lang.String.compareTo` on the raw cell text — UTF-16 code-unit
   order, locale-independent (all symbols are ASCII, so this equals byte order);
5. `\n` line endings, one trailing `\n`, no `\r`, no blank lines, no trailing
   whitespace, UTF-8 (`StandardCharsets.UTF_8`) with no BOM;
6. no timestamps, no absolute paths, no jar hashes anywhere in the file.

Consumers skip all leading `#` lines and parse the remaining lines as data rows; this
keeps the exact per-file schema header stable while making provenance part of every
checked-in artifact.

Because the full column tuple is unique per row, the order is total and regeneration
against an unchanged Cats produces a zero-byte diff. Overload disambiguators
(`cats/Foldable#foldM().`, `cats/Foldable#foldM(+1).`) follow the SemanticDB
convention and are assigned in **declaration order** as returned by
`Symbol.declarations`, which TASTy preserves.

### Fallback

If TASTy inspection stalls in #37 (e.g. an inspector regression on 3.8.4), fall back to
the bootstrap route #33 allows: hand-write `typeclasses.tsv` and `capabilities.tsv` for
the typeclasses named in the #33 capability list, mark the header
`# source: bootstrap (hand-written)`, and rely on item 8's audit to make every
unlisted typeclass a test failure. Do **not** silently narrow scope. The smoke test
above means this is unlikely; it is recorded so the fallback is a decision, not an
improvisation.

---

## 2. Capability IR shape

**Decision: `capability = (typeclass, method, owner, kind, derived, arity)`, with
inheritance flattened at index time and the `owner` column carrying the override-chain
root.**

A *capability* is the atom the analyzer produces and the solver consumes. Its identity
is `owner` — the **root of the method's override chain** —
`Symbol.allOverriddenSymbols.lastOption.getOrElse(self)` in the generator.

This matters more than it sounds. A naive "the method is named `map`" keying is wrong
in both directions, as the real index shows:

```
method map      -> declared in: Functor, Applicative, Monad, Traverse
method flatMap  -> declared in: FlatMap, Parallel
```

`Applicative.map`, `Monad.map` and `Traverse.map` are *overrides* of `Functor.map` —
same capability, so they collapse onto `owner = cats/Functor#map().`.

One Cats 2.13.0 TASTy detail required a narrow refinement to the planned rule.
`Parallel.flatMap` is a concrete, zero-argument evidence accessor returning
`FlatMap[M]`; TASTy reports that it overrides the abstract
`NonEmptyParallel.flatMap` accessor. Treating those accessors as one operation would
lose the design's required distinction from the ordinary `FlatMap.flatMap`
capability. The implemented structural rule therefore roots a concrete zero-argument
accessor that returns an indexed typeclass at itself; every other method uses
`allOverriddenSymbols.lastOption.getOrElse(self)`. Thus
`Parallel.flatMap` has `owner = cats/Parallel#flatMap().` without keying on its
method name.

**Syntax extension methods** get their own table, `syntax.tsv`. The generator walks
every `cats.syntax.*` ops class, and for each public method whose enclosing ops class
or method takes an implicit/`using` parameter of some `TC[F]`, emits
`syntaxMethod -> (owner, method)` where `(owner, method)` is the capability rooted as
above, plus the `importPath` needed to make the syntax available in emitted code
(`cats.syntax.functor.*`). The analyzer resolves a call site to a symbol and looks it
up in `syntax.tsv` first, then `capabilities.tsv`, then `stdlib.tsv`. A call site whose
symbol is in none of the three is a decline (`NoCapability`), never a silent skip.

**Inherited methods are flattened at index time.** `capabilities.tsv` carries one row
per `(typeclass, method)` pair for *every* method a typeclass provides, own or
inherited, with `owner` naming the declaring root.

Cost consequence, measured rather than estimated: the row count is
`Σ_tc |methods(tc)|` rather than `Σ_tc |ownMethods(tc)|`, but the real Cats 2.13.0
inventory is 1,471 capability rows (130,314 bytes), not the previously projected low
tens of thousands. The representation is still deliberately flattened. It buys a
solver that is a set-membership test over a `Map[Symbol, List[Capability]]` with no
hierarchy walk per query, and a `provides(tc)` answer that is a single lookup.
`typeclasses.tsv` keeps *direct* parents so the lattice remains reconstructible for
ranking.

---

## 3. Law-defined / derived methods

**Decision: derived methods are index entries, flagged `derived = true`. A derived entry
may satisfy a capability check but may never be the sole justification for a rewrite.**

"Derived" means: the method's meaning comes from the typeclass's laws or a default
implementation in terms of other members, rather than from being the primitive the
typeclass exists to name. `Applicative.map` (law-derivable from `ap` and `pure`) and
`Traverse.sequence` (`traverse(identity)`) are the canonical cases. The generator sets
`derived = true` when `owner != typeclass` **or** the declaration is concrete (has a
body) rather than abstract.

The rule this produces is precise:

- **Satisfaction** — "does typeclass `T` provide capability `c`?" — consults all rows,
  derived included. `Monad` does provide `map`.
- **Justification** — "which typeclass does this call site *require*?" — resolves to
  `owner` only, i.e. the primitive root. A `map` call justifies `cats/Functor#`, never
  `cats/Applicative#`, even though `Applicative` also declares `map`.

Consequence: a derived row can never be the minimal answer, because its `owner` is
always at least as shallow and is what the analyzer emits. So the answer to "may a
derived entry be the sole justification for a rewrite?" is **no**, structurally, not by
a check that could be forgotten.

If a capability's override-chain root is itself derived everywhere it appears — no
primitive owner exists — the analyzer declines with `NoCapability` and the typeclass is
a `gaps.tsv` candidate. That is a real, if rare, outcome, and it is reported rather
than guessed.

---

## 4. Visibility threshold for widening

**Decision: private and package-private only. Public defs decline with exactly one
warning. A `widenPublic` config flag (default `false`) opts in.**

Widening a public signature from `List[User]` to `G[User]` is a source-and-binary
breaking change for every downstream caller. The rule cannot see those callers, so it
cannot prove the change safe, and #33 already asks it to "preserve concrete types at
application/public API boundaries unless the rule can prove widening is intended and
safe". It never can. `private[pkg]` is the widest scope where the compilation unit's
own module bounds the blast radius.

**The decline rule, precisely** (this is what `AbstractPublicBoundaryDecline` asserts):

A candidate `Defn.Def` is *widenable* iff `config.widenPublic` is `true`, **or** at
least one of the following holds:

1. the def carries `Mod.Private` or `Mod.Protected` with **any** `within` (i.e.
   `private`, `private[x]`, `protected[x]`, `private[this]`);
2. the def is a local definition — its nearest enclosing `Defn.Def`, `Term.Block`,
   `Term.Function`, or `Term.Anonymous` exists (a def inside a def is never part of an
   API);
3. every enclosing template-owner in the chain from the def to the compilation unit
   root (`Defn.Class`, `Defn.Trait`, `Defn.Object`) carries `Mod.Private` or
   `Mod.Protected`.

`protected` **without** a `within` is *not* widenable: it is visible to unknown
subclasses outside the module.

A def that is not widenable, but that the analyzer would otherwise have rewritten
(i.e. every other check passed and the solver returned a `Solution`), produces
**no patch and exactly one** `LintSeverity.Warning` at the def's name position, with
reason `DeclineReason.PublicBoundary(name)`. A def that is not widenable *and* would
have been declined for another reason produces the other reason's warning only — the
visibility gate is evaluated last, so warnings never double up.

Severity is `Warning`, not `Error`, for the same reason `ArrowBudgetDiagnostic` is:
scalafix withholds every patch in a file that reports a lint error, which would
silently disable the rule's other rewrites in that file.

---

## 5. Ranking rule

**Decision: candidate sets are ordered ascending by the triple
`(constraintCount, strengthSum, symbolsLexicographic)`. The order is total; ties are
impossible.**

### Definitions

- `depth(tc)` = the number of *indexed Cats typeclasses* that are strict ancestors of
  `tc`. Computed from `baseClasses` at index time and stored in `typeclasses.tsv`.
  Because `ancestors(B) ⊋ ancestors(A)` whenever `A` is a strict ancestor of `B`, the
  measure is strictly monotone along the lattice by construction — no tie-breaking
  needed inside it.
- `strengthSum(S)` = `Σ depth(tc)` over the constraint set `S`.
- `symbolsLexicographic(S)` = element-wise `String.compareTo` over `S`'s symbols sorted
  ascending; a proper prefix sorts first.

Real depths, computed from `cats-core_3:2.13.0`:

| tc | depth | tc | depth | tc | depth |
| --- | --- | --- | --- | --- | --- |
| `Invariant` | 0 | `Functor` | 1 | `Apply` | 4 |
| `Semigroupal` | 0 | `Contravariant` | 1 | `FlatMap` | 5 |
| `SemigroupK` | 0 | `MonoidK` | 1 | `Applicative` | 6 |
| `Defer` | 0 | `Foldable` | 1 | `ApplicativeError` | 7 |
| `FunctorFilter` | 0 | `TraverseFilter` | 1 | `Monad` | 8 |
| `Bifunctor` | 0 | `Reducible` | 2 | `NonEmptyTraverse` | 7 |
| `Bifoldable` | 0 | `CoflatMap` | 2 | `Alternative` | 10 |
| `UnorderedFoldable` | 0 | `Comonad` | 3 | `MonadError` | 10 |
| `Parallel` | 1 | `Traverse` | 5 | `Bitraverse` | 2 |

### Candidate enumeration (kept finite)

Given required capability owners `R`:

1. **Single-constraint candidates:** every indexed typeclass `T` with a matching kind
   whose flattened capability set contains all of `R`. Each is a candidate `[T]`.
2. **Multi-constraint candidate:** the antichain reduction of `R` — take the distinct
   owners of `R`, then drop any that is a strict ancestor of another in the set (the
   descendant already provides it). This yields exactly one candidate set.

If the antichain has more than `config.maxConstraints` (default `2`) members and step 1
produced nothing, the solver declines with
`DeclineReason.TooManyConstraints(candidate, max)`.

### Why count first

Cats' typeclasses *are* the named joins of their capabilities; `Monad` exists precisely
so that "flatMap and pure" has one name. Ranking by strength alone would answer
`FlatMap` + `Applicative` for a monadic body and `Functor` + `Semigroupal` for a body
that maps and products — technically weaker sets, but they push a laws-and-coherence
distinction into every signature the rule touches, and #33's own worked outcomes
(`AbstractMonadFlatMapPure`, `AbstractAlternativeEmptyAndChoice`) name the joined
typeclass. Count-first also directly serves #33's "smallest signature change" and
"readable output" criteria. Strength still decides everything *within* a given
constraint count, so the rule never picks `Monad` where `Functor` would do.

### Worked examples

**A — one stronger vs two weaker (`AbstractMonadFlatMapPure`).**
Body uses `flatMap` and `pure`. Owners: `{cats/FlatMap#flatMap(). (depth 5),
cats/Applicative#pure(). (depth 6)}`.

| candidate | count | strengthSum | note |
| --- | --- | --- | --- |
| `[Monad]` | 1 | 8 | provides both |
| `[MonadError]` | 1 | 10 | provides both |
| `[Alternative]` | 1 | 10 | provides both |
| `[FlatMap, Applicative]` | 2 | 11 | antichain of owners |

Key 1 eliminates the antichain. Key 2 picks `Monad` (8 < 10). **Winner: `cats/Monad#`.**

**B — no single typeclass covers the set (`AbstractFunctorFilter`).**
Body uses `mapFilter` and `map`. Owners: `{cats/FunctorFilter#mapFilter(). (0),
cats/Functor#map(). (1)}`. `FunctorFilter` does not extend `Functor` in Cats — it holds
one as a member — so step 1 yields **no** single-constraint candidate. The antichain
`[Functor, FunctorFilter]` (count 2, sum 1) is the only candidate.
**Winner: `[cats/Functor#, cats/FunctorFilter#]`**, rendered
`[G[_]: Functor: FunctorFilter]` in symbol order.

**C — key 3 exercised.** Two distinct sets with equal count and equal strength sum, e.g.
`[Contravariant (1), FunctorFilter (0)]` and `[Functor (1), FunctorFilter (0)]` — both
count 2, sum 1. Key 3 compares `cats/Contravariant#` against `cats/Functor#`;
`C` < `F`, so the first wins. Key 3 exists so that the order is total by construction:
two distinct symbol sets always differ lexicographically, so `rank` never returns a tie
and `solve` never has to report ranking ambiguity. (`AbstractAmbiguousWeakestCapability`
therefore asserts *capability-resolution* ambiguity, not ranking ambiguity — see item 9.)

### Existing-constraint reuse

Reuse is **not** a ranking key. It is applied earlier: if the enclosing scope already
declares a type parameter of matching kind whose constraints are a superset of a
candidate solution's, `HktRewriter` reuses that parameter and emits no new constraint at
all (`AbstractExistingConstraintReuse`). Making reuse a ranking key would let it change
*which* typeclass is chosen; making it a rewriting decision only lets it change *how*
the chosen answer is spelled.

---

## 6. Kind shapes supported in v1

**Decision: `Star` and `Unary` are in. `Binary` is indexed but not solved. Type lambdas
are out.**

| shape | v1 | meaning |
| --- | --- | --- |
| `KindShape.Star` | **in** | value-level abstraction: `A` with `Monoid[A]`, `Semigroup[A]`, `Order[A]` |
| `KindShape.Unary` | **in** | `F[_]` — `Functor`, `Monad`, `Traverse`, … |
| `KindShape.Binary` | **indexed, declined** | `F[_, _]` — `Bifunctor`, `Bifoldable`, `Bitraverse` |
| type lambda | **out** | `[X] =>> Either[E, X]` |

Rationale for cutting `Binary` and type lambdas:

1. Scala 3 has no `Either[E, *]` — that is kind-projector syntax, available only under
   `-Ykind-projector`, which this build does not set. Native syntax is
   `[X] =>> Either[E, X]`, which the rewriter would have to synthesize.
2. Abstracting `Either[String, Int]` to `F[Int]` requires callers to infer
   `F = [X] =>> Either[String, X]`. Scala 3 does not reliably infer a partially-applied
   type constructor from an applied binary type, so the rewrite compiles at the
   definition and breaks at the call sites — the worst possible failure mode for a rule
   whose expected outputs must compile.
3. Choosing *which* parameter to fix adds a whole solve dimension (left-fixed vs
   right-fixed vs bifunctorial) that has no bearing on the unary case.

**Important distinction:** the shape being decided is the kind of the *abstracted type
constructor*, not the arity of the *typeclass*. `MonadError[F, E]` is in scope — `F` is
`Unary`, and `E` is a proper type carried through `Solution.extraTypeParams` (or left
concrete when the source pins it, e.g. `MonadError[F, Throwable]` for `Try`).
Likewise `Parallel[M]`, `FunctorFilter[F]` and `Defer[F]` are all `Unary`.

### Tracked gaps

Cut shapes become `gaps.tsv` rows, not silence:

```
cats/Bifunctor#	binary kind F[_, _] not solved in v1; needs type-lambda rendering	#33
cats/Bifoldable#	binary kind F[_, _] not solved in v1; needs type-lambda rendering	#33
cats/Bitraverse#	binary kind F[_, _] not solved in v1; needs type-lambda rendering	#33
```

### Consequence for #33's fixture list

Three of #33's 22 positive fixture names name shapes that are out of v1. They are
**re-designated, not deleted** — the names survive, the assertion flips:

| fixture | v1 status |
| --- | --- |
| `AbstractBifunctorEitherBimap` | negative: no patch + one `UnsupportedKind(Binary)` warning |
| `AbstractBitraverse` | negative: no patch + one `UnsupportedKind(Binary)` warning |
| `AbstractTypeLambdaEitherRight` | negative: no patch + one `UnsupportedKind(Binary)` warning |

They are promoted back to positive when the binary-kind phase lands. v1 therefore has
**19 positive and 13 negative** fixtures, still 32 files.

---

## 7. Module boundaries

Files, following the existing layout (`PreferArrow.scala` at `scalafix/src/fix/`, helpers
at `scalafix/src/fix/arrow/`):

| File | Contents |
| --- | --- |
| `scalafix/src/fix/hkt/CapabilityIR.scala` | `KindShape`, `Capability`, `CatsTypeclass` |
| `scalafix/src/fix/hkt/CatsIndex.scala` | index loader + lattice queries |
| `scalafix/src/fix/hkt/UsageAnalyzer.scala` | `RequiredOp`, `DeclineReason`, `UsageResult`, `UsageAnalyzer` |
| `scalafix/src/fix/hkt/CapabilitySolver.scala` | candidate enumeration, ranking, `solve` |
| `scalafix/src/fix/hkt/HktRewriter.scala` | signature/body/import rendering |
| `scalafix/src/fix/PreferHKTTypeclasses.scala` | rule shell, config, diagnostic |
| `scalafix/indexgen/src/fix/hkt/gen/CatsIndexGen.scala` | TASTy generator (not published) |
| `scalafix/resources/META-INF/services/scalafix.v1.Rule` | add `fix.PreferHKTTypeclasses` |

Six seams, deliberately wide. Every signature below **compiles** against
`scalafix-rules_3.8.4:0.14.7` + `cats-core:2.13.0` on Scala 3.8.4 — verified before
this document was committed, with the files laid out exactly as above. `???` marks a
body the implement leaves fill in; nothing else is elided.

Note for implementers: the module builds with `-Wunused:imports -Werror`, so each real
file must import only what it uses. The import blocks below are the union across seams.

### `scalafix/src/fix/hkt/CapabilityIR.scala`

```scala
package fix.hkt

import scalafix.v1.Symbol

/** Kind of the type constructor under analysis. */
sealed trait KindShape

object KindShape {
  case object Star extends KindShape
  case object Unary extends KindShape
  case object Binary extends KindShape

  def arity(shape: KindShape): Int = ???
  def parse(token: String): Option[KindShape] = ???
  def render(shape: KindShape): String = ???
}

final case class Capability(
    typeclass: Symbol,
    method: Symbol,
    owner: Symbol,
    kind: KindShape,
    derived: Boolean,
    arity: Int
)

final case class CatsTypeclass(
    symbol: Symbol,
    parents: List[Symbol],
    kind: KindShape,
    typeParamCount: Int,
    depth: Int,
    renderName: String,
    importPath: String,
    isPublic: Boolean
)
```

### `scalafix/src/fix/hkt/CatsIndex.scala`

```scala
package fix.hkt

import scalafix.v1.Symbol

final class CatsIndex(
    val typeclasses: Map[Symbol, CatsTypeclass],
    val capabilities: Map[Symbol, List[Capability]],
    val syntax: Map[Symbol, Capability]
) {
  def providersOf(method: Symbol): List[Capability] = ???
  def primitiveOwner(method: Symbol): Option[Symbol] = ???
  def resolveSyntax(method: Symbol): Option[Capability] = ???
  def isAncestor(ancestor: Symbol, descendant: Symbol): Boolean = ???
  def depth(typeclass: Symbol): Int = ???
  def publicTypeclasses: List[CatsTypeclass] = ???
}

object CatsIndex {
  val capabilitiesResource: String = "cats-index/capabilities.tsv"
  val typeclassesResource: String = "cats-index/typeclasses.tsv"
  val gapsResource: String = "cats-index/gaps.tsv"

  def load(): CatsIndex = ???
  def parse(
      typeclassRows: Iterator[String],
      capabilityRows: Iterator[String]
  ): Either[String, CatsIndex] = ???
}
```

`capabilities` is keyed by **typeclass** symbol (flattened, per item 2); `providersOf`
inverts it for a method. `parse` returns `Left` with the offending line so a malformed
artifact fails loudly instead of yielding an empty index.

### `scalafix/src/fix/hkt/UsageAnalyzer.scala`

```scala
package fix.hkt

import scala.meta.inputs.Position

import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol

final case class RequiredOp(
    method: Symbol,
    position: Position,
    kind: KindShape
)

sealed trait DeclineReason {
  def message: String
}

object DeclineReason {
  final case class ConcreteConstructorMatch(what: String) extends DeclineReason {
    def message: String = ???
  }
  final case class OrderOrIndexSpecific(what: String) extends DeclineReason {
    def message: String = ???
  }
  final case class UnsupportedKind(shape: KindShape) extends DeclineReason {
    def message: String = ???
  }
  final case class PublicBoundary(defName: String) extends DeclineReason {
    def message: String = ???
  }
  final case class AmbiguousCapability(candidates: List[Symbol]) extends DeclineReason {
    def message: String = ???
  }
  final case class NoCapability(method: Symbol) extends DeclineReason {
    def message: String = ???
  }
  final case class UnsafeBody(what: String) extends DeclineReason {
    def message: String = ???
  }
  final case class NameConflict(tried: List[String]) extends DeclineReason {
    def message: String = ???
  }
  final case class TooManyConstraints(candidate: List[Symbol], max: Int)
      extends DeclineReason {
    def message: String = ???
  }
  case object MissingEvidence extends DeclineReason {
    def message: String = ???
  }
}

sealed trait UsageResult

object UsageResult {
  final case class Abstractable(
      defn: scala.meta.Defn.Def,
      target: scala.meta.Type,
      constructor: Symbol,
      elementType: scala.meta.Type,
      ops: List[RequiredOp]
  ) extends UsageResult

  final case class Declined(position: Position, reason: DeclineReason) extends UsageResult
}

object UsageAnalyzer {
  def analyze(defn: scala.meta.Defn.Def, index: CatsIndex, widenPublic: Boolean)(
      implicit doc: SemanticDocument
  ): List[UsageResult] = ???

  def isWidenable(defn: scala.meta.Defn.Def, widenPublic: Boolean)(implicit
      doc: SemanticDocument
  ): Boolean = ???
}
```

`analyze` returns a list because one `def` may mention several concrete constructors;
each is analysed independently and yields its own `Abstractable` or `Declined`.

### `scalafix/src/fix/hkt/CapabilitySolver.scala`

```scala
package fix.hkt

import scalafix.v1.Symbol

object CapabilitySolver {
  final case class Solution(
      constraints: List[Symbol],
      extraTypeParams: List[String],
      strengthSum: Int
  )

  def solve(ops: List[RequiredOp], index: CatsIndex, maxConstraints: Int)
      : Either[DeclineReason, Solution] = ???

  def candidates(ops: List[RequiredOp], index: CatsIndex): List[List[Symbol]] = ???

  def rank(candidates: List[List[Symbol]], index: CatsIndex): List[List[Symbol]] = ???

  def supports(typeclass: Symbol, index: CatsIndex): Boolean = ???
}
```

Pure: no `SemanticDocument`, no tree, no I/O. `rank` returns the candidates in the
total order of item 5, best first. `supports` is what the item-8 audit calls.

### `scalafix/src/fix/hkt/HktRewriter.scala`

```scala
package fix.hkt

import scalafix.v1.Patch
import scalafix.v1.SemanticDocument

object HktRewriter {
  def rewrite(
      usage: UsageResult.Abstractable,
      solution: CapabilitySolver.Solution,
      index: CatsIndex,
      typeParamName: String
  )(implicit doc: SemanticDocument): Patch = ???

  def freshTypeParamName(
      defn: scala.meta.Defn.Def,
      preferred: List[String]
  ): Option[String] = ???

  def requiredImports(
      solution: CapabilitySolver.Solution,
      index: CatsIndex
  ): List[String] = ???
}
```

`freshTypeParamName` is tried against `List("G", "H", "K")` in that order and returns
`None` if all three are taken by an enclosing or local type parameter — the trigger for
`DeclineReason.NameConflict`. Every `Patch` is anchored on a `doc.tree` node; the
rewriter never re-parses `doc.input.text` (`docs/RULES.md`).

### `scalafix/src/fix/PreferHKTTypeclasses.scala`

```scala
package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.hkt.CatsIndex
import fix.hkt.DeclineReason

final case class PreferHKTTypeclassesConfig(
    widenPublic: Boolean = false,
    maxConstraints: Int = 2
)

object PreferHKTTypeclassesConfig {
  val default: PreferHKTTypeclassesConfig = PreferHKTTypeclassesConfig()
  implicit val decoder: ConfDecoder[PreferHKTTypeclassesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("widenPublic")(default.widenPublic)
        .product(conf.getOrElse("maxConstraints")(default.maxConstraints))
        .map(PreferHKTTypeclassesConfig.apply.tupled)
    }
}

final case class HKTDeclineDiagnostic(
    override val position: scala.meta.inputs.Position,
    reason: DeclineReason
) extends Diagnostic {
  override def message: String = reason.message
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

final class PreferHKTTypeclasses(config: PreferHKTTypeclassesConfig)
    extends SemanticRule("PreferHKTTypeclasses") {

  def this() = this(PreferHKTTypeclassesConfig.default)

  private lazy val index: CatsIndex = CatsIndex.load()

  override def withConfiguration(configuration: Configuration): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferHKTTypeclasses")(PreferHKTTypeclassesConfig.default)
      .map(new PreferHKTTypeclasses(_))

  override def fix(implicit doc: SemanticDocument): Patch = ???
}
```

The config shape mirrors `PreferArrowConfig` exactly, including the
`ConfDecoder.from` + `product` + `apply.tupled` idiom, so it needs no metaconfig
derivation macro.

---

## 8. Gap-audit contract

**File:** `scalafix/resources/cats-index/gaps.tsv`
**Test:** `scalafix/test/src/fix/hkt/CatsIndexAuditSuite.scala`

Columns (tab-separated, `#`-prefixed header, sorted by column 1 per item 1's rule):

| column | meaning |
| --- | --- |
| `typeclass` | SemanticDB symbol, e.g. `cats/Bitraverse#` |
| `reason` | non-empty free text: *why* it is unsupported |
| `tracked` | issue reference (`#33`) or the literal `none` |

The suite enumerates `index.publicTypeclasses` — **no hard-coded typeclass list** — and
asserts four things:

1. **No unlisted gap.** For every `tc` with `isPublic = true`, either
   `CapabilitySolver.supports(tc.symbol, index)` is `true`, or `tc.symbol` appears in
   `gaps.tsv`.
2. **No stale gap.** Every `gaps.tsv` row whose typeclass *is* supported fails.
3. **No orphan gap.** Every `gaps.tsv` typeclass exists in `typeclasses.tsv`.
4. **Well-formed.** Every row has exactly 3 columns, a non-empty `reason`, and the file
   is sorted.

Failure message shapes, fixed here so the fixture and the test agree:

```
Unsupported Cats typeclass is not listed in scalafix/resources/cats-index/gaps.tsv:
  cats/Bitraverse#  (kind=Binary, depth=2)
Either teach CapabilitySolver to support it, or add a row:
  cats/Bitraverse#<TAB><why it is unsupported><TAB>#33
```

```
Stale gap in scalafix/resources/cats-index/gaps.tsv:
  cats/Foldable#  is now supported by CapabilitySolver; remove this row.
```

```
Orphan gap in scalafix/resources/cats-index/gaps.tsv:
  cats/NotAThing#  is not present in scalafix/resources/cats-index/typeclasses.tsv.
```

`supports(tc, index)` is defined as: `CapabilitySolver.solve` returns a `Right` for the
capability set consisting of every non-derived capability whose `owner`'s typeclass is
`tc` — i.e. the typeclass's own primitives. A typeclass whose kind is not in v1 (item 6)
returns `false`, which is exactly what puts `Bifunctor`/`Bifoldable`/`Bitraverse` in
`gaps.tsv`.

---

## 9. Fixture matrix

**Layout:** executed fixtures only — `scalafix/testInput/src/golden/<Name>.scala` and
`scalafix/testOutput/src/golden/<Name>.scala`, **flat names**, matching relative paths,
consistent with `docs/GOLDEN_FIXTURES.md`. Nothing is added under
`scalafix/test/resources/golden` (unexecuted; that doc forbids new fixtures there).

**Rule header** — every fixture, positive and negative, opens with:

```scala
/*
rules = [PreferHKTTypeclasses]
 */
package golden
```

`AbstractPublicBoundaryDecline` additionally pins the default explicitly, because that
is the behaviour under test:

```scala
/*
rules = [PreferHKTTypeclasses]

PreferHKTTypeclasses.widenPublic = false
 */
package golden
```

Negative fixtures assert their warning inline, in the style of
`ArrowFlowFanOutNegativeShadow.scala`:

```scala
  private def head(xs: List[Int]): Int = xs.head // assert: PreferHKTTypeclasses
```

and their `testOutput` file is byte-identical to the input except that the
`// assert:` comment is not carried over by the testkit's expected-output comparison —
copy a neighbouring `Arrow*` negative pair for the exact convention. Every declined
fixture asserts **exactly one** warning and **zero** edits.

All input defs are `private` unless the fixture is specifically about visibility.

### Positive fixtures (19)

| # | Fixture | Input shape | Expected output shape |
| --- | --- | --- | --- |
| 1 | `AbstractFunctorListMap` | `private def names(us: List[User]): List[String] = us.map(_.name)` | `private def names[G[_]: Functor](us: G[User]): G[String] = us.map(_.name)`; adds `cats.Functor`, `cats.syntax.functor.*` |
| 2 | `AbstractApplyMap2` | `private def pair(xs: List[Int], ys: List[Int]): List[Int] = xs.map2(ys)(_ + _)` (`cats.syntax.apply.*`) | `[G[_]: Apply]`, both params and result `G[Int]`; adds `cats.Apply` |
| 3 | `AbstractApplicativePure` | body uses `.map` and `List(x).pure`-style `pure` | `[G[_]: Applicative]`; `Applicative` beats `Monad` on key 2 |
| 4 | `AbstractFlatMapDependent` | `xs.flatMap(f).map(g)`, no `pure` | `[G[_]: FlatMap]` — owners `{FlatMap, Functor}`, `Functor` is an ancestor so the antichain is `[FlatMap]`, and `FlatMap` (5) beats `Monad` (8) |
| 5 | `AbstractMonadFlatMapPure` | `xs.flatMap(f)` and `pure` | `[G[_]: Monad]` — worked example A of item 5 |
| 6 | `AbstractTraverseListTraverse` | `private def all(xs: List[Int]): Option[List[Int]] = xs.traverse(f)` with `f: Int => Option[Int]` | `[G[_]: Traverse](xs: G[Int]): Option[G[Int]]` — the inner `Option` stays concrete; only the outer constructor is abstracted |
| 7 | `AbstractFoldableListFoldMap` | `xs.foldMap(_.toString)` | `[G[_]: Foldable]`, result stays `String` |
| 8 | `AbstractReducibleNonEmpty` | `NonEmptyList[Int]` + `reduceLeftTo` | `[G[_]: Reducible](xs: G[Int])` |
| 9 | `AbstractMonoidEmptyAndCombine` | `private def fold(xs: List[String]): String = xs.foldLeft("")(_ \|+\| _)` | `KindShape.Star`: `private def fold[A: Monoid](xs: List[A]): A` — the *element* is abstracted, the container is not |
| 10 | `AbstractSemigroupKCombineK` | `xs.combineK(ys)` on `List` | `[G[_]: SemigroupK]` |
| 11 | `AbstractAlternativeEmptyAndChoice` | `pure`, `combineK`, and `MonoidK`'s `empty` | `[G[_]: Alternative]` — owners `{Applicative(6), SemigroupK(0), MonoidK(1)}`, antichain drops `SemigroupK`; single-constraint `Alternative` wins on key 1 |
| 12 | `AbstractMonadErrorEitherRaiseHandle` | `scala.util.Try[Int]`, `raiseError` + `recoverWith` + `flatMap` | `private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int]`. **Deviation from the name:** the input uses `Try`, not `Either`. `Either[String, Int] => F[Int]` needs a type lambda at every call site, which item 6 puts out of v1; `Try` exercises the same `MonadError` path at `Unary` kind. The name is kept so #33's list stays traceable. |
| 13 | `AbstractFunctorFilter` | `xs.mapFilter(f)` and `xs.map(g)` | `[G[_]: Functor: FunctorFilter]` — worked example B of item 5 |
| 14 | `AbstractTraverseFilter` | `xs.traverseFilter(f)` only | `[G[_]: TraverseFilter]` |
| 15 | `AbstractContravariantContramap` | `private def byName(s: Show[String]): Show[User] = s.contramap(_.name)` | `private def byName[G[_]: Contravariant](s: G[String]): G[User]` |
| 16 | `AbstractInvariantImap` | `Semigroup[String]` + `.imap` | `[G[_]: Invariant]` — `Invariant` (0) is the override-chain root of `imap` |
| 17 | `AbstractComonadExtractCoflatMap` | `Eval[Int]` + `.coflatMap(w => w.extract + 1)` | `[G[_]: Comonad]` |
| 18 | `AbstractDefer` | `Eval.defer(...)` recursion | `[G[_]: Defer]`, body uses `Defer[G].defer(...)` |
| 19 | `AbstractExistingConstraintReuse` | class already declares `[G[_]: Traverse]`; a private def takes `List[User]` and only maps | reuses `G`: `private def names(us: G[User]): G[String]` — **no** new type parameter, **no** new constraint, `cats.syntax.functor.*` import only if absent |

### Negative fixtures (13)

Every row: **no patch + exactly one warning**, at the position given.

| # | Fixture | Input trigger | `DeclineReason` | Warning position |
| --- | --- | --- | --- | --- |
| 1 | `AbstractConcretePatternMatch` | `xs match { case Nil => 0; case h :: t => ... }` | `ConcreteConstructorMatch("Nil")` | the `case Nil` pattern |
| 2 | `AbstractConcreteOrderSpecific` | `xs.sorted.head` | `OrderOrIndexSpecific("head")` | the `.head` call |
| 3 | `AbstractConcreteOptionBranchingWithoutCapability` | `if (o.isDefined) o.get else d` | `ConcreteConstructorMatch("isDefined")` | the `.isDefined` call |
| 4 | `AbstractConcreteEitherLeftSpecificWithoutBifunctor` | `e.left.map(f)` on `Either[String, Int]` | `UnsupportedKind(Binary)` | the def name |
| 5 | `AbstractPublicBoundaryDecline` | a **public** def otherwise identical to fixture 1 of the positive table | `PublicBoundary("names")` | the def name |
| 6 | `AbstractAmbiguousWeakestCapability` | `xs.reduce(_ + _)` on `List` | `AmbiguousCapability(List(cats/Reducible#reduceLeft()., cats/Semigroup#combine().))` — `stdlib.tsv` maps `reduce` to two unrelated capability roots, and `List` is not provably non-empty | the `.reduce` call |
| 7 | `AbstractTypeParamNameConflict` | enclosing scope declares type params named `G`, `H` **and** `K` | `NameConflict(List("G", "H", "K"))` | the def name |
| 8 | `AbstractMissingCatsEvidence` | `private def total[B](xs: List[B]): B = xs.foldMap(identity)` — `foldMap` needs `Monoid[B]`, `B` has no such bound | `MissingEvidence` | the `.foldMap` call |
| 9 | `AbstractMutableOrThrowingBody` | body contains `var acc` and `throw new IllegalStateException(...)` | `UnsafeBody("var")` | the `var` definition |
| 10 | `AbstractUnsupportedCatsApiGapFailsIndexAudit` | uses a `gaps.tsv`-listed API (`Bitraverse`-shaped) | `UnsupportedKind(Binary)`; paired with `CatsIndexAuditSuite` proving the symbol is listed | the def name |
| 11 | `AbstractBifunctorEitherBimap` | `e.bimap(f, g)` on `Either[String, Int]` | `UnsupportedKind(Binary)` — **re-designated from positive**, see item 6 | the def name |
| 12 | `AbstractBitraverse` | `e.bitraverse(f, g)` | `UnsupportedKind(Binary)` — **re-designated from positive** | the def name |
| 13 | `AbstractTypeLambdaEitherRight` | `private def widen(e: Either[String, Int]): Either[String, String] = e.map(_.toString)` | `UnsupportedKind(Binary)` — **re-designated from positive**; abstracting the right slot needs `[X] =>> Either[String, X]` | the def name |

---

## Phase / subtask breakdown

The child issues already exist. This is the shape they should have after this design
lands; where a child contradicts a decision above, the child is amended (item-by-item
list in the #36 hand-off), not the design.

| # | Leaf | Phase | Depends on | Runner tier | Why that tier |
| --- | --- | --- | --- | --- | --- |
| 37 | Generate + check in the Cats capability inventory | source-of-truth | #36 | **strong** (`codex/gpt-5/high`, `claude/opus`) | TASTy inspector API + override-chain rooting + a new mill module; the smoke test above de-risks it but does not write it |
| 38 | Capability IR + index loader | implement | #36, #37 | **mid** (`claude/sonnet`, `codex/gpt-5/medium`) | pure parsing against a fixed format and fixed signatures |
| 39 | Usage analyzer | implement | #36, #38 | **strong** (`codex/gpt-5/high`, `claude/opus`) | highest-risk leaf: Scala 3 extension-method and `cats.syntax` symbol resolution is fiddly |
| 40 | Solver | implement | #36, #38 | **mid** (`codex/gpt-5/medium`) | the algorithm is fully specified in item 5, including candidate enumeration and the total order |
| 41 | Rewriter + rule shell | implement | #36, #39, #40 | **strong** (`codex/gpt-5/high`, `claude/opus`) | patch rendering, constraint-style detection, idempotence |
| 42 | Positive fixtures (19) | test | #36, #41 | **cheap** (`claude/haiku`, `claude/sonnet`) | the matrix states input and output shape per row |
| 43 | Negative fixtures (13) + gap audit | test | #36, #41 | **cheap→mid** (`claude/sonnet`) | matrix-driven; the audit test needs the fixed message shapes from item 8 |
| 44 | Corpus run | test | #42, #43 | **mid** (`claude/sonnet`) | mechanical execution plus judgement calls recorded in a report |

Ordering constraint worth naming: #42 and #43 are cheap **only because** items 5, 6, 8
and 9 are decided here. A fixture author who has to re-derive whether `{flatMap, pure}`
means `Monad` or `FlatMap + Applicative` is doing design, not fixtures, and needs a
strong runner. Keep the matrix authoritative.

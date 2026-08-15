# PreferPolymorphicTypeclasses — design

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
| `rtk mill scalafix.catsIndex` | `Task.Command` wrapper that regenerates the four generated artifacts in place and preserves the audited, hand-written `stdlib.tsv` — **the documented command** |
| `rtk mill scalafix.catsIndexCheck` | regenerates into `Task.dest` and fails on any byte difference from the four checked-in generator-owned files |

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
| `stdlib.tsv` | `concreteMethod`, `kind`, `owner`, `method`, `note` |
| `gaps.tsv` | `typeclass`, `reason`, `tracked` |

Snapshot for Cats 2.13.0 on Scala 3.8.4 (data rows exclude headers):

| File | Data rows | Bytes |
| --- | ---: | ---: |
| `typeclasses.tsv` | 59 | 5,337 |
| `capabilities.tsv` | 1,471 | 130,439 |
| `syntax.tsv` | 354 | 42,998 |
| `stdlib.tsv` | 74 | 7,863 |
| `gaps.tsv` | 20 | 2,499 |
| **Total** | **1,978** | **189,136** |

TSV, not JSON: the rows are flat and uniform, a TSV diff is reviewable line-by-line in
a PR, and parsing needs no dependency. `symbol`/`method`/`owner` are **SemanticDB symbol
strings** (`cats/Functor#`, `cats/Functor#map().`) so they compare directly against
`scalafix.v1.Symbol` values obtained from a `SemanticDocument`. Multi-valued cells
(`parents`) are `,`-separated, themselves sorted by the same rule.

`stdlib.tsv` is hand-written and reviewed because it records policy rather than facts
that can be derived from Cats TASTy. It maps concrete stdlib/Cats-data methods
(`scala/collection/immutable/List#map().`) either to the Cats capability they demand
or to a decline. Its second header line is exactly
`# hand-written; audited by CatsIndexDriftSuite`; regeneration leaves the file
untouched, while the drift suite checks capability targets, decline names, duplicate
keys, stable sorting, and canonical bytes. The concrete symbols in the checked-in
snapshot were taken from SemanticDB emitted by Scala 3.8.4 for calls on `List`,
`Option`, `Vector`, `Map`, and `Try`.

For `kind = capability`, `owner` and `method` are the `(owner, method)` pair of
an existing `capabilities.tsv` row. For `kind = decline`, `owner` is empty and
`method` is one of `ConcreteConstructorMatch`, `OrderOrIndexSpecific`, or
`UnsafeBody`. Two rows with the same `concreteMethod` are allowed only when both
are capabilities; this is how `IterableOnceOps#reduce()` records the unrelated
`Reducible.reduceLeft` and `kernel.Semigroup.combine` candidates. Policy-defined
`gaps.tsv` rows remain generator-owned.

Concrete symbols are the declarations recorded at real call sites, not synthesized from
the receiver type. For example, `List#reduce` resolves to
`scala/collection/IterableOnceOps#reduce().`, while `foldMap`, `traverse`, `mapFilter`,
`NonEmptyList#reduceLeftTo`, `Eval#coflatMap`, and `Comonad#extract` resolve to Cats ops
symbols already covered by `syntax.tsv`; `stdlib.tsv` does not duplicate those syntax
mappings.

### Stable sort — exact specification

Every generator-owned file is written as:

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

`stdlib.tsv` uses the same row sort, comparison, encoding, newline, and trailing
newline rules. It differs only in being hand-written and using the audit header
described above instead of the generated-source header.

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
up in `syntax.tsv` first, then `capabilities.tsv`, then `stdlib.tsv`. A stdlib match
returns every stably ordered `StdlibEntry` for that concrete symbol: capability
entries participate in capability selection, while a decline entry produces its
reviewed `DeclineReason`. Multiple unrelated capability entries produce
`AmbiguousCapability`. A call site whose symbol is in none of the three is a decline
(`NoCapability`), never a silent skip.

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
| `scalafix/src/fix/PreferPolymorphicTypeclasses.scala` | rule shell, config, diagnostic |
| `scalafix/indexgen/src/fix/hkt/gen/CatsIndexGen.scala` | TASTy generator (not published) |
| `scalafix/resources/META-INF/services/scalafix.v1.Rule` | add `fix.PreferPolymorphicTypeclasses` |

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

sealed trait StdlibMapping

object StdlibMapping {
  final case class ToCapability(owner: Symbol, method: Symbol)
      extends StdlibMapping
  final case class ToDecline(reason: String) extends StdlibMapping
}

final case class StdlibEntry(
    concreteMethod: Symbol,
    mapping: StdlibMapping
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
    val syntax: Map[Symbol, Capability],
    val stdlib: Map[Symbol, List[StdlibEntry]]
) {
  def providersOf(method: Symbol): List[Capability] = ???
  def primitiveOwner(method: Symbol): Option[Symbol] = ???
  def resolveSyntax(method: Symbol): Option[Capability] = ???
  def resolveStdlib(method: Symbol): List[StdlibEntry] = ???
  def syntaxImport(method: Symbol): Option[String] = ???
  def isAncestor(ancestor: Symbol, descendant: Symbol): Boolean = ???
  def depth(typeclass: Symbol): Int = ???
  def publicTypeclasses: List[CatsTypeclass] = ???
}

object CatsIndex {
  val capabilitiesResource: String = "cats-index/capabilities.tsv"
  val typeclassesResource: String = "cats-index/typeclasses.tsv"
  val syntaxResource: String = "cats-index/syntax.tsv"
  val stdlibResource: String = "cats-index/stdlib.tsv"
  val gapsResource: String = "cats-index/gaps.tsv"

  def load(): CatsIndex = ???
  def parse(
      typeclassRows: Iterator[String],
      capabilityRows: Iterator[String],
      syntaxRows: Iterator[String],
      stdlibRows: Iterator[String]
  ): Either[String, CatsIndex] = ???
}
```

`capabilities` is keyed by **typeclass** symbol (flattened, per item 2); `providersOf`
inverts it for a method. `parse` returns `Left` with the offending line so a malformed
artifact fails loudly instead of yielding an empty index.

**Deviation from the original sketch above (applied in #38, same commit):** the
sketch predates the final #37 artifact and omits two things it needs. First,
`syntax.tsv` and `stdlib.tsv` need their own resource-path constants alongside
`capabilitiesResource`/`typeclassesResource`/`gapsResource`, mirroring the five
checked-in files from item 1 — `syntaxResource` and `stdlibResource` above.
Second, `parse` cannot build the `syntax: Map[Symbol, Capability]` and
`stdlib: Map[Symbol, List[StdlibEntry]]` fields from only the typeclass and
capability iterators, so it takes both `syntaxRows: Iterator[String]` and
`stdlibRows: Iterator[String]`, as reflected in the signature above (the
original sketch took only `typeclassRows` and `capabilityRows`).

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

### `scalafix/src/fix/PreferPolymorphicTypeclasses.scala`

```scala
package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.hkt.CatsIndex
import fix.hkt.DeclineReason

final case class PreferPolymorphicTypeclassesConfig(
    widenPublic: Boolean = false,
    maxConstraints: Int = 2
)

object PreferPolymorphicTypeclassesConfig {
  val default: PreferPolymorphicTypeclassesConfig = PreferPolymorphicTypeclassesConfig()
  implicit val decoder: ConfDecoder[PreferPolymorphicTypeclassesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("widenPublic")(default.widenPublic)
        .product(conf.getOrElse("maxConstraints")(default.maxConstraints))
        .map(PreferPolymorphicTypeclassesConfig.apply.tupled)
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

final class PreferPolymorphicTypeclasses(config: PreferPolymorphicTypeclassesConfig)
    extends SemanticRule("PreferPolymorphicTypeclasses") {

  def this() = this(PreferPolymorphicTypeclassesConfig.default)

  private lazy val index: CatsIndex = CatsIndex.load()

  override def withConfiguration(configuration: Configuration): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferPolymorphicTypeclasses")(PreferPolymorphicTypeclassesConfig.default)
      .map(new PreferPolymorphicTypeclasses(_))

  override def fix(implicit doc: SemanticDocument): Patch = ???
}
```

The config shape mirrors `PreferArrowConfig` exactly, including the
`ConfDecoder.from` + `product` + `apply.tupled` idiom, so it needs no metaconfig
derivation macro.

---

## 7a. Rendering contract

This section is normative for the rewriter (#66), rule shell (#67), and executed
fixtures (#42/#43). Where a general preference elsewhere in this document leaves
formatting freedom, this section wins.

### Syntax-import lookup and required import set

`syntax.tsv`'s fourth column is retained by `CatsIndex`. The additive query
`syntaxImport(method: Symbol): Option[String]` accepts either the syntax-wrapper symbol
from column 1 or the resolved primitive owner/method carried by `RequiredOp.method`. It
returns column 4 verbatim. When inherited syntax creates several rows for the same
primitive, the row whose `*.Ops` symbol normalizes directly to that primitive wins;
ties fall back to `(syntaxMethod.value, importPath)` ascending. Consequently
`cats/Functor#map().` maps to `cats.syntax.functor.*`, not the inherited
`cats.syntax.apply.*` row. `resolveSyntax` remains keyed by column 1 and is unchanged.

The fixed two-argument seam cannot see `UsageResult.Abstractable.ops` because
`CapabilitySolver.Solution` contains only `constraints`, `extraTypeParams`, and
`strengthSum`. Its exact compatibility behaviour is therefore:

```scala
HktRewriter.requiredImports(solution, index)
```

returns the distinct `CatsTypeclass.importPath` values for `solution.constraints`,
sorted by ordinary `String.compareTo`. `rewrite` then computes its final import list as:

```scala
requiredImports(solution, index) ++ usage.ops.flatMap(op => index.syntaxImport(op.method))
```

and normalizes that expression into one combined list containing:

1. every typeclass import from the two-argument result; and
2. `index.syntaxImport(op.method)` for every `op` actually present in `usage.ops`.

Missing syntax-import metadata is not guessed and contributes no string. The combined
strings are deduplicated by exact string equality and then sorted ascending with
`String.compareTo`; typeclass imports are not placed in a separate group. For the
Functor smoke case the result is exactly:

```text
cats.Functor
cats.syntax.functor.*
```

Before rendering, remove an import if an equivalent import is already in lexical scope
at the candidate def. Equivalence is determined from the existing `Import` trees, not
by substring matching: a direct import or selector of the same path suppresses it, and
an enclosing wildcard import suppresses a member beneath that prefix. Thus
`import cats.Functor`, `import cats.{Functor}`, and `import cats.*` each suppress a new
`cats.Functor`; `import cats.syntax.functor.*` suppresses that exact module import.
Aliases and exclusions do not suppress the unaliased import. Imports in an unrelated
local block are not in scope and do not suppress anything.

An in-scope `import cats.syntax.all.*` suppresses **every** generated
`cats.syntax.<module>.*` import. It does not suppress typeclass imports. Existing imports
keep their original spelling and order; only the remaining newly emitted strings are
sorted, and they are inserted as one contiguous block.

### Signature rewrite

For a unary `UsageResult.Abstractable`, walk only the declared parameter types and the
declared result type of `usage.defn`. At every `Type.Apply` whose head resolves to
`usage.constructor`, replace the head node with the chosen type-parameter name and do
not descend into that application's arguments. Descend through non-matching wrappers
so a target nested below an unrelated result wrapper is still reached. This is a
pre-order, outermost-match rule:

```text
List[A]                 -> G[A]
Option[List[A]]         -> Option[G[A]]
List[List[A]]           -> G[List[A]]
```

The last line is intentional: once the outer matching application is rewritten, a
nested occurrence of the same concrete constructor remains concrete. Every matching
outer occurrence in every parameter clause and in the result type is rewritten; no
term in the body and no local type annotation is rewritten.

Preserve `elementType` byte-for-byte by patching only the constructor-head tree
(`Type.Apply.tpe`), never by printing a replacement `Type.Apply` from scratch. For
`AbstractTraverseListTraverse`, this produces
`(xs: G[Int]): Option[G[Int]]`: `List` is the selected constructor, while the unrelated
outer result wrapper `Option` stays concrete. The body is not rewritten.

### Constraint-style detection

Style detection scans the one enclosing `Source` represented by `doc.tree`, including
all definitions and constructors in that source. It inspects:

- every non-empty `Type.Param` context-bound list; and
- every parameter clause marked `using`, whether its evidence parameter is named or
  anonymous.

Comments, strings, imports, and trees outside that `Source` do not participate. If the
source contains at least one `using` clause, render a `using` clause. Otherwise, if it
contains at least one context bound, render context bounds. On a mixed file, `using`
wins. If the source contains neither style, default to context bounds.

`solution.extraTypeParams.nonEmpty` overrides that file-style result and always forces
a **named** `using` clause. Context-bound syntax cannot render the additional applied
typeclass arguments without synthesizing a type lambda. Name the evidence parameter
after the new or reused type parameter. Fixture 12 therefore renders exactly:

```scala
private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int]
```

When `extraTypeParams` is empty, context-bound style renders constraints in
`solution.constraints` symbol order, for example `[G[_]: Functor: FunctorFilter]`.
Using style renders one named evidence parameter per constraint, in the same order; use
the type-parameter name for a single constraint and append stable numeric suffixes
`2`, `3`, ... when there are several (`G`, `G2`, `G3`).

### Existing-constraint reuse

Search visible type parameters from the candidate def outward through enclosing defs
and template owners, innermost scope first and source order within one owner. A candidate
matches the requested kind exactly: `A` for `Star`, `G[_]` for `Unary`. Read its Cats
constraints from both of these sources and union them:

1. context bounds written on that `Type.Param`; and
2. in-scope `using` evidence parameters whose type is an indexed Cats typeclass applied
   to that candidate type parameter.

Resolve every typeclass through SemanticDB. For an applied constraint with additional
arguments, those non-constructor arguments must be semantically identical to the
solution's rendered arguments; in particular `MonadError[G, Throwable]` does not match
`MonadError[G, DomainError]`. A candidate constraint set is a superset of the solution
iff, for every required typeclass `R`, the set contains some `C` where `C == R` or
`index.isAncestor(R, C)`; a stronger constraint therefore satisfies a weaker one.
This makes an existing `Traverse[G]` a valid superset of a `Functor[G]` solution.

Choose the first matching candidate in the search order. Rewrite concrete constructor
heads to that parameter, but emit no type parameter and no constraint. Do not emit the
solution's typeclass imports: the existing evidence is already in scope. Emit only the
syntax imports required by `usage.ops`, after applying the existing-import suppression
rules above. This is the exact contract for `AbstractExistingConstraintReuse`.

### Patch anchoring

Every patch anchor is a node reachable from the original `doc.tree`:

- When the def has no type parameters, insert the complete new type-parameter clause
  with `Patch.addRight(defn.name, renderedTypeParams)`. `defn.name` is the anchor; do
  not parse `doc.input.text` merely to manufacture a missing type-parameter-list node.
- When a type-parameter clause exists, append relative to its last original
  `Type.Param`; constraint edits anchor to the original `Type.Param` or original using
  parameter clause involved.
- Parameter type replacements anchor to each matching original `Type.Apply.tpe` below
  a `Term.Param.decltpe`.
- Result type replacements anchor to each matching original `Type.Apply.tpe` below
  `defn.decltpe`.
- Import insertion anchors after the last in-scope top-level/package `Import`. If there
  is none, anchor after the enclosing unbraced package's `Pkg.ref`; for a source without
  a package, anchor before its first original top-level `Stat`. A candidate def implies
  such a stat exists.

No edit may anchor to a tree obtained by reparsing `doc.input.text`, even when the def
has no existing type parameters. Positions from a second parse are forbidden by
`docs/RULES.md`.

### Idempotence

The current `UsageAnalyzer.signatureTargets`/`outerConcreteTargets` partially provides
idempotence: an applied head whose symbol is a type parameter fails the
`isGlobal && !isTypeParameter` test, so `G[A]` itself is not returned as a concrete
target. It then descends into that application's arguments, however, and other concrete
applications elsewhere in the signature (for example the preserved `Option` in
`Option[G[A]]`) can still be discovered. Analyzer target discovery alone is therefore
not a sufficient second-run guard.

The shell must not call the analyzer for a def when both conditions hold:

1. a declared parameter or result type contains a `Type.Apply` whose head resolves to
   a visible type parameter; and
2. that type parameter has at least one visible indexed Cats constraint according to
   the context-bound/using lookup defined above.

Such a def produces no candidate, no diagnostic, and no patch. Before the first rewrite
`AbstractExistingConstraintReuse` has a constrained enclosing `G` but no `G[...]` in
the candidate def's signature, so it does not trip the guard; after reuse it does. This
predicate also makes every newly introduced context-bound or using-style abstraction a
no-op on the second run.

### Byte-exact smoke fixtures

These are complete file bodies for the two #67 smoke pairs. #67 copies them verbatim to
the flat executed-fixture paths shown. The negative pair follows
`ArrowFlowFanOutNegativeShadow.scala`: its input places `// assert:` on the diagnostic
line, while its expected output omits that testkit-only assertion comment.

#### `scalafix/testInput/src/golden/AbstractFunctorListMap.scala`

```scala
/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

final case class FunctorUser(name: String)

object AbstractFunctorListMap {
  private def names(users: List[FunctorUser]): List[String] =
    users.map(_.name)
}
```

#### `scalafix/testOutput/src/golden/AbstractFunctorListMap.scala`

```scala
/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.Functor
import cats.syntax.functor.*

final case class FunctorUser(name: String)

object AbstractFunctorListMap {
  private def names[G[_]: Functor](users: G[FunctorUser]): G[String] =
    users.map(_.name)
}
```

#### `scalafix/testInput/src/golden/AbstractPublicBoundaryDecline.scala`

```scala
/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = false
 */
package golden

final case class PublicBoundaryUser(name: String)

object AbstractPublicBoundaryDecline {
  def names(users: List[PublicBoundaryUser]): List[String] = // assert: PreferPolymorphicTypeclasses
    users.map(_.name)
}
```

#### `scalafix/testOutput/src/golden/AbstractPublicBoundaryDecline.scala`

```scala
/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = false
 */
package golden

final case class PublicBoundaryUser(name: String)

object AbstractPublicBoundaryDecline {
  def names(users: List[PublicBoundaryUser]): List[String] =
    users.map(_.name)
}
```

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
rules = [PreferPolymorphicTypeclasses]
 */
package golden
```

`AbstractPublicBoundaryDecline` additionally pins the default explicitly, because that
is the behaviour under test:

```scala
/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = false
 */
package golden
```

Negative fixtures assert their warning inline, in the style of
`ArrowFlowFanOutNegativeShadow.scala`:

```scala
  private def head(xs: List[Int]): Int = xs.head // assert: PreferPolymorphicTypeclasses
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
| 6 | `AbstractAmbiguousWeakestCapability` | `xs.reduce(_ + _)` on `List` | `AmbiguousCapability(List(cats/Reducible#reduceLeft()., cats/kernel/Semigroup#combine().))` — `stdlib.tsv` maps `reduce` to two unrelated capability roots, and `List` is not provably non-empty | the `.reduce` call |
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

## Conclusions

`stdlib.tsv` has the final column layout `concreteMethod`, `kind`, `owner`,
`method`, `note`. `kind` is `capability` or `decline`; decline names are restricted
to `ConcreteConstructorMatch`, `OrderOrIndexSpecific`, and `UnsafeBody`.

The stable analyzer-facing signatures are:

```scala
sealed trait StdlibMapping

object StdlibMapping {
  final case class ToCapability(owner: Symbol, method: Symbol)
      extends StdlibMapping
  final case class ToDecline(reason: String) extends StdlibMapping
}

final case class StdlibEntry(
    concreteMethod: Symbol,
    mapping: StdlibMapping
)
```

---

## 10. As built — the wiring, and where it departs from this document

The rule shell now runs on the engine (`UsageAnalyzer` → `CapabilitySolver` →
`HktRewriter`) instead of the fixture-name table it shipped with. What landed
differs from the sections above in the following places; each is a decision, and
the sections above stay as the record of what was intended.

| Decision | As built | Why |
| --- | --- | --- |
| Config type | `PreferPolymorphicTypeclassesConfig(rewrite, widenPublic = false, maxConstraints = 2)`; the old `PreferHKTConfig` and a `PreferPolymorphicTypeclasses(PreferHKTConfig)` constructor are retained | item 7's shape, plus `rewrite` for parity with the container rule. The old names stay because MiMa checks this module against the last release |
| Kind shapes | `Unary` only | `Star` widening is `PreferPolymorphicCollectionOps`' subject (`A: Monoid`), and nothing in this rule renders a `Star` target. `Binary` is declined as item 6 says |
| Type-parameter names | `G`, `H`, `K` | as in negative fixture 7, which needs all three taken to reach `NameConflict` |
| `DeclineReason.NoCapability` | never reported | the analyzer declines on the *first* unresolvable call anywhere in the body, usually an element-level one (`String#toInt`). Reporting it would warn on most definitions. Same rationale as `PreferPolymorphicCollections.mentionsContainer` |
| Body rewriting | none, as item 7a's rendering contract requires | which makes fixtures 8, 12, 13, 14 and 18 (`Reducible[NonEmptyList]`, `Try(...)`, `FunctorFilter[Option]`, `TraverseFilter[List]`, `Eval.defer`) **no-ops** rather than the rewrites their rows describe: each body names the concrete constructor, and only a body rewrite could carry it over. They are kept as fixtures asserting exactly that, and the shapes are listed under `docs/RULES.md` "Candidate Rules" |
| Fixture 9 (`AbstractMonoidEmptyAndCombine`) | widens the *container* to `[A: Monoid, G[_]: Foldable]` rather than the element | the input's `foldLeft` is a `Foldable` capability on `xs`; element abstraction is the other rule |
| `MissingEvidence` | not produced | no element-evidence check exists; the fixture supplies `Monoid[B]` locally so the widened form compiles |
| Stale imports | the widened constructor's import is removed when nothing else in the file names it | otherwise `import cats.Eval` survives a rewrite that deleted its last use, and `-Wunused:imports -Werror` turns our rewrite into a build failure |
| Infix calls | `Term.ApplyInfix` counts as a call on its left-hand side | `x <+> y` is `combineK`, and Cats syntax is written infix. Infix calls do not *extend* a chain: `w.extract + 1` is arithmetic on an element, not an operation on the container |
| `<+>` | added to the hand-written `stdlib.tsv` | the generator emits an `Ops` row per capability *name*, so operator aliases (`<+>` → `combineK`) have no row. One audited row states the alias; a general alias pass would have to read `Ops` method bodies |
| Visibility | the analyzer is always asked `widenPublic = true`, and the rule applies `UsageAnalyzer.isWidenable` to the *solved* result | asking the analyzer to decide it reports `PublicBoundary` for every public definition that merely mentions a concrete type. On `skreeep2`'s `core` that was 17 warnings, of which 1 was about a definition anything could have widened |
| Declines | reported only when the signature names a unary constructor `CatsIndex.knowsConstructor` recognises | otherwise `def sumPlanes(out: FloatBuffer, base: Int): Unit` is told its `var` blocks an abstraction that never existed. Trialling on `skreeep2` this cut the whole-repo warning count from 52 to 12, with no rewrite lost |
| Overrides | new `DeclineReason.InheritedSignature`, raised in `UsageAnalyzer` for a def that declares `override` or has `overriddenSymbols` | found by the same trial: the rule widened `override def apply[A](fa: Seq[A]): List[A]` into `apply[A, G[_]: Foldable]`, which implements nothing and does not compile. It is a decline for every consumer of the engine, so it belongs to the analyzer, not to this rule |

### Call sites, and the shape of the trial that found them

The `skreeep2` trial drove three further decisions, all about the *other* end of
a widened signature:

| Decision | As built | Why |
| --- | --- | --- |
| Chain exit | `capabilities.tsv` gained an `exits` column, generated from TASTy: whether a capability's result mentions the abstracted constructor. `UsageAnalyzer` stops growing a call chain at an exit, and `ContainerFlow` stops accounting there | `xs.toList.zipWithIndex.map(f)` is a `Foldable` use of `xs` and then a `List` expression. Read as one chain it declines as order-specific; read with the exit it widens, which is what `SinesGenerator.fromRatios` needed |
| Rule overlap | `PreferPolymorphicTypeclasses.containers` defaults to the collections, which it leaves to `PreferPolymorphicCollections` | run together on one signature the two rules produced `names[S[_]: Functor][G[_]: Functor](users: SG[String])` |
| Call sites | `fix.container.WidenScope` (see `docs/RULES.md`), opt-in per rule as `crossFile` | widening `fromRatios[T]` to `fromRatios[T, S[_]]` compiled `signals` and broke `score`, whose `fromRatios[V](...)` names one type argument too few |

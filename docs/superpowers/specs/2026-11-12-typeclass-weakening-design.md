# TypeclassWeakening — Generalised Design

**Status: approved for implementation (2026-11-12).** Supersedes the
Functor/Applicative/Monad-only target selection committed in `f97b75a`.

## Purpose

Replace an over-strong context bound on an effect type parameter with the
single weakest ancestor typeclass that still provides everything the body
uses. Works for any typeclass hierarchy reachable on the classpath — cats,
cats-effect, and third-party or user libraries — not a fixed list.

```scala
def bump[F[_]: Sync](fa: F[Int]): F[Int] = fa.map(_ + 1)        // -> Functor
def both[F[_]: Sync](a: F[Int], b: F[Int]) = (a, b).mapN(_ + _) // -> Apply
def safe[F[_]: Sync](fa: F[Int]) = fa.handleError(_ => 0)       // -> ApplicativeThrow
def eff[F[_]: Sync](x: => Int): F[Int] = Sync[F].delay(x)       // unchanged
```

## Decisions (from the user)

1. **Any ancestor is a target**, intermediates included (`Apply`, `FlatMap`,
   `Semigroupal`, `MonadCancel`, ...).
2. **Exactly one bound is emitted.** Never split one bound into several
   (`F[_]: Functor: Foldable`): several bounds sharing an ancestor make syntax
   such as `map` ambiguous. If the needs span two branches, the single
   typeclass that covers both is chosen; the original is kept if nothing
   weaker covers both.
3. **Typeclass facts are read from the libraries themselves**, not from a
   hand-maintained table: hierarchy, method owners and method evidence come
   from TASTy on the scalac classpath. SemanticDB covers the user's own code.
4. **Per-run performance matters**: results are cached.

## Why TASTy, not SemanticDB, for libraries

Verified 2026-11-12: with `cats-core_3-2.13.0.jar` and
`cats-effect-kernel_3-3.7.0.jar` on the testkit classpath, `Symbol.info` is
`None` for `cats/Monad#`, `cats/Functor#map().`, `cats/effect/kernel/Sync#`
and `Sync#delay().`. Scalafix's classpath symbol table reads Scala 2 pickles
and Java classfiles, not Scala 3 TASTy. The facts exist in TASTy and
`scalafix/indexgen` already extracts them with `scala3-tasty-inspector`.

## Architecture

```
scalac classpath ─▶ JarLocator ─▶ TastyScanCache ─▶ TypeclassInspector (TASTy)
                     (symbol→jar)   (bundled │ disk │ memory)       │
                                          └──────── rows (TSV) ◀────┘
                                                    │
                                          CatsIndex.parse(...)  ← existing model
                                                    │
 SemanticDocument ─▶ Requirements ─▶ BoundSolver ─▶ Decision ─▶ Patch / lint
```

- **TypeclassInspector** (moved from `indexgen` into the published module,
  parameterised by a package filter): emits the existing typeclass /
  capability / syntax rows plus two new tables:
  - `evidence`: method → typeclasses of its implicit/using parameters
    (`Traverse#traverse` → `Applicative`).
  - `aliases`: unary type aliases over binary typeclasses with fixed
    arguments (`MonadThrow` = `MonadError[F, Throwable]`,
    `MonadCancelThrow` = `MonadCancel[F, Throwable]`).
  `indexgen` delegates to it; the checked-in cats tables stay byte-identical.
- **Index sources, in order**: (1) bundled resources when the jar is the
  bundled cats version; (2) disk cache; (3) TASTy scan, then written to disk.
  An in-JVM memo keyed by classpath serves every document of a run.
- **Scan scope**: only jars holding the bounds seen in the document, then
  the jars of their ancestors, transitively — never the whole classpath.
- **Disk cache**: `~/.cache/scala-purrism/tasty-index/<key>/`, key =
  SHA-1 of `absolutePath|size|mtime|formatVersion|scalaVersion`. Written to a
  temp dir then atomically renamed. Configurable / disableable.

## Requirement collection

For a bound `B` on `F` in owner `O` (def, class, trait), the set `R` of
typeclasses `O` needs from `F`:

- **Direct calls**: each `Term.Name` whose symbol resolves (syntax table or
  primitive owner) to a capability whose declaring typeclass is `B` or an
  ancestor of `B`. Owners outside `B`'s ancestry constrain another type and
  are ignored (`xs.traverse`'s `Traverse`).
- **Evidence synthetics**: every synthetic application handed `F`'s evidence
  must be explained by one of: an indexed capability; a `toXOps` conversion
  (Scala 3 records it on the same span as `fa(using ev)`); the `evidence`
  table (library methods); SemanticDB signature (local methods). An
  unexplained one ⇒ keep `B`.
- **Local helpers**: a call to a local method whose own bound is being
  weakened contributes the helper's *weakened* bound (memoised fixpoint;
  cycles contribute the helper's original bound).
- **for-comprehensions**: rely on their evidence synthetics; if those do not
  explain the desugared calls, the evidence rule keeps `B`.

## Solving

`BoundSolver.solve(B, R, index)`:

- `R` empty, or `B ∈ R`, or some `r ∈ R` is not `B`/ancestor-of-`B` ⇒ `Keep`.
- Candidates `C` = unary-renderable typeclasses that are `B` or ancestors of
  `B`, and are `r` or descendants of `r` for every `r ∈ R`. Binary
  typeclasses are renderable only through an alias whose fixed arguments
  match `B`'s parent clause.
- Minimal elements of `C` (no other candidate is their ancestor):
  one ⇒ `Replace(t)`; several ⇒ `Ambiguous(options)` (keep `B`, emit
  `TypeclassWeakening.ambiguous-weakening` lint); `{B}` ⇒ `Keep`.
- Idempotent: solving `t` with the same `R` yields `Keep`.

## Failure behaviour

No classpath, unreadable jar, TASTy version newer than the rule's compiler,
missing symbol ⇒ `Keep`, never a guess. With `debug = true` the rule logs
index source (bundled / disk / scan), jars scanned and time spent.

## Configuration

```hocon
TypeclassWeakening {
  cacheDir = "~/.cache/scala-purrism/tasty-index"  # "" disables disk cache
  debug = false
}
```

## Out of scope

Removing a bound that nothing uses; weakening bounds on non-unary type
parameters; multi-bound output.

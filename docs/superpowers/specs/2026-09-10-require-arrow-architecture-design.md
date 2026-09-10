# RequireArrowArchitecture — Design

## Purpose

A standalone scalafix lint rule enforcing a restricted vocabulary on selected
packages/directories: in-scope files may only *name* types from a configured
whitelist and may not use a configured set of syntactic constructs. It is
diagnostic-only — it never rewrites code — because there is no safe
automatic rewrite from arbitrary logic into arrow form.

Unlike this project's other rules, it does not migrate code toward a style;
it is a boundary/gate that keeps already-conforming code from drifting, on
whichever packages/directories a team opts in.

The default configuration reproduces an "arrow architecture" layer: code may
only name `cats.arrow.*` types, `Either`, `Option`, `Tuple`, and its own
package's types — and may not use `if`/`match`/`var`/`for`/`while`/`try`. A
concrete effect/arrow implementation (`Function1`, `Kleisli`, `IO`, ...)
never gets named inside such a file; only assembled and composed from
already-abstract pieces. But the engine itself has no arrow-specific
knowledge — it is a generic "restricted vocabulary" checker. A team can
reuse it to gate any other architecture layer by pointing `scope`/`classes`
at a different package and a different whitelist.

This replaces an earlier design (see git history) that modeled "arrow slot"
as a typeclass-bound-resolution problem (`Arrow`/`Compose`/`Category`
instance search, `given`-chain walking, a special-cased `ArrowConvert`
shape, a monomorphism rule, a companion-object-only evidence carve-out).
That machinery is dropped entirely: everything it was trying to prevent
falls out for free once *any* named type must come from a fixed whitelist —
there is no separate case left to special-case.

## Configuration

Configuration splits into two layers: a **profile** — a reusable, named
bundle of the actual vocabulary rules (what's allowed, what's banned) — and
the **per-module settings** that pick a profile and narrow where it applies.
A profile is defined once and referenced by name, so multiple modules (or
multiple scopes within one module) can share the same vocabulary without
repeating its `classes`/`bannedConstructs`/etc. lists.

```hocon
RequireArrowArchitecture {
  severity = warning        // or error
  scope = [                 // empty (the default) = the whole module
    "com\\.foo\\.wiring\\..*"
  ]
  profile = "arrow"
  profiles {
    arrow {
      classes = [
        "cats\\.arrow\\..*",
        "scala\\.Either",
        "scala\\.Option",
        "scala\\.Tuple.*"
      ]
      bannedConstructs = [
        "scala.meta.Term.If",
        "scala.meta.Term.Match",
        "scala.meta.Defn.Var",
        "scala.meta.Term.For",
        "scala.meta.Term.While",
        "scala.meta.Term.Try"
      ]
      budgetedTypeclasses = ["ArrowConvert"]
      maxInstantiations = 1
    }
  }
}
```

Every list entry (`scope`, a profile's `classes`) is either a full class
name (matched exactly against a symbol's FQCN) or a regexp (matched against
it) — one syntax, no separate glob dialect.

`severity` defaults to `warning`, matching `docs/RULES.md`'s convention;
promote to `error` per-module once a package is fully conformant.

`profile` names which entry in `profiles` supplies the vocabulary; an
unresolvable name (empty `profiles`, or a name not among its keys) fails
config validation loudly rather than silently falling back to an empty
(fully permissive) profile.

`scope` empty means the *whole module* is in scope — this is the opposite
of the old "empty means nothing" default; a module already opts into the
rule by listing it, so an empty `scope` narrows nothing further. A
non-empty `scope` restricts to files whose own package matches one of the
patterns, same as before.

## Scope

A file is in scope if its own top-level package/class is matched by any
`scope` entry. `scope` entries are also implicitly part of the type
whitelist (see below) — a scoped file can always reference its own
package's other types without repeating the pattern in `classes`.

## Type whitelist

Every *named* type appearing in a scoped file's declarations — member
types, parameter/return types, `extends`/`with` clauses, type arguments —
must resolve to a symbol matched by `scope` or `classes`. This is a single
provenance check, nothing more: no typeclass-bound resolution, no instance
search.

Type parameters and abstract type members are always exempt from this
check: they have no external provenance to test, so ordinary genericity
(`trait Pipeline[Step[_, _]]`, `def make[Step[_, _]](...)`) is unrestricted
— a generic method or type doesn't *name* anything, it only ever
*receives* whatever the caller supplies. Naming a concrete type outside the
whitelist is the only thing this check forbids, e.g. writing `Kleisli[IO,
Int, Int]` directly, or importing and naming `scala.concurrent.Future`.

Because `cats.arrow.*` is on the default whitelist, ordinary uses like
`Step[_, _]: Arrow` (naming the `Arrow` typeclass itself as a context
bound) are fine — that's a whitelisted type, named in the ordinary way.
What stays forbidden is naming a *concrete arrow implementation*, since
nothing implements one inside `cats.arrow.*`, the current package, or
`Either`/`Option`/`Tuple`.

Imports are unrestricted (a tooling concern, not an architectural one) —
importing `cats.data.Kleisli` doesn't itself violate anything; only
*naming* it in a declaration does.

## Banned constructs

`bannedConstructs` names Scalameta tree classes (e.g. `scala.meta.Term.If`).
A node in a scoped file is a violation if its runtime type `isInstanceOf`
any configured class — subtype-inclusive, so a single entry naming a common
supertype (where Scalameta's hierarchy has one) disables every subtype at
once, rather than needing one entry per concrete node kind.

Default set bans `if`, `match`, `var`, `for`, `while`, `try` — the
imperative/branching vocabulary that isn't "compose already-existing
values." A lambda literal with a non-trivial body is banned the same way
by naming the relevant `Term.Function` shape; a bare eta-expanded reference
(`step _` or point-free) isn't a `Term.Function` and isn't touched by this
check.

## Conversion budget

`budgetedTypeclasses` (default `["ArrowConvert"]`) names typeclass symbols
this rule counts requirements for. For each configured typeclass and each
trait/case-class + companion-object pair, the rule counts the number of
*distinct* type-argument tuples for which that pair requires evidence
(context bound or `given`/implicit parameter) anywhere across the two
templates. Requiring the same tuple twice (e.g. in two different companion
constructors) counts once. Exceeding `maxInstantiations` (default `1`) is
reported once per trait/case-class pair, at the configured `severity`.

`ArrowConvert` itself is not special-cased by the engine — it's an ordinary
trait a team defines in its own scoped package:

```scala
trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}
```

It passes the type/construct checks like any other in-scope declaration (a
generic method that only ever applies its own enclosing type's parameters
names nothing outside the whitelist); the budget check is the only place
its symbol is referenced by name, purely to know what to count.

## Diagnostics

One `LintSeverity` diagnostic per violating declaration, anchored on the
member/definition whose shape breaks (not per sub-expression token), per
`docs/RULES.md`'s "report at the granularity of the decision, not of the
evidence." Message names which check failed and what was found:

> member `run` names `cats.data.Kleisli[cats.effect.IO, Int, Int]`; only
> types matching this file's configured scope/classes whitelist may be
> named here

> `if` is a banned construct in this scope

> trait/case-class pair `LivePipeline` requires 2 distinct instantiations
> of `ArrowConvert`, exceeding the configured max of 1

## Examples

### Conforming

```scala
package com.foo.wiring

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

### Violating

```scala
package com.foo.wiring

import cats.arrow.Arrow
import cats.data.Kleisli
import cats.effect.IO

final case class BadHolder[Step[_, _]: Arrow](
  step: Step[Int, Int]
) {
  var cache: Map[Int, Int] = Map.empty   // ✗ banned construct: var

  def run(x: Int): Int =
    if (cache.contains(x)) cache(x) else x   // ✗ banned construct: if
}

final case class NamesConcreteArrow(
  step: Kleisli[IO, Int, Int]   // ✗ Kleisli/IO not in scope/classes whitelist
) {}

final case class TooManyConversions[P[_, _], Q[_, _], R[_, _]](
  p: P[Int, Int]
)(implicit
  pq: ArrowConvert[P, Q],   // 2 distinct ArrowConvert instantiations —
  qr: ArrowConvert[Q, R]    // ✗ exceeds default maxInstantiations = 1
)
```

## Implementation approach

Single-pass structural + syntactic walk over each in-scope file (no
project-wide closure needed, since every check resolves from the reference
site's own symbol, not derived transitively):

1. **Scope**: match the file's top-level package/class FQCN against `scope`.
2. **Type whitelist**: for every named type in a declaration, resolve its
   symbol (SemanticDB) and match its FQCN against `scope` ∪ `classes`; skip
   type parameters and abstract type members (no symbol to resolve outside
   the declaring template).
3. **Banned constructs**: syntactic tree traversal; for each node, check
   `isInstanceOf` against every configured Scalameta class.
4. **Budget**: for each `budgetedTypeclasses` symbol, collect required
   type-argument tuples (context bounds / `given`/implicit params) across
   each trait/case-class + companion pair, dedupe, compare to
   `maxInstantiations`.

Checks 2–4 are independent and can run in one traversal; nothing needs
`Arrow`/`Compose`/`Category` instance resolution or `given`-chain walking.

## Registration & testing

- File: `scalafix/src/fix/RequireArrowArchitecture.scala`.
- Registered in `scalafix/resources/META-INF/services/scalafix.v1.Rule`
  alongside the existing rules.
- Executed fixtures under `scalafix/testInput`/`testOutput`, per
  `docs/GOLDEN_FIXTURES.md` — diagnostic-only, so input and output source
  are identical; fixtures assert emitted lint messages/positions.
- Cases to cover: conforming file exercising the default arrow-layer
  configuration end to end; each banned construct individually (`if`,
  `match`, `var`, `for`, `while`, `try`); a named type outside
  `scope`/`classes` (both a random unrelated type and a plausible
  near-miss like `Function1`); an out-of-scope file with the same
  violations producing no diagnostics; self-reference to another type in
  the same scoped package (conforming, no need to list it in `classes`);
  a type matched by regex vs. by exact FQCN in `classes`; `bannedConstructs`
  entry naming a Scalameta supertype disabling multiple concrete node kinds
  at once (if the hierarchy allows it) vs. one entry per concrete kind;
  `budgetedTypeclasses` at exactly `maxInstantiations` (conforming), one
  over (violation), and the same `(P, Q)` pair required twice across
  template and companion counting once, not twice; a second
  `RequireArrowArchitecture` instance configured for an unrelated layer
  (different `scope`/`classes`) in the same project, to demonstrate the
  engine's reuse beyond the arrow use case.

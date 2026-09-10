# RequireArrowArchitecture — Design

## Purpose

A new, standalone scalafix lint rule that enforces a restricted architectural
style on selected packages/directories: definitions there may only express
themselves as **arrows** (`Function1`, `Kleisli`/`Reader`, or any type with a
resolvable `cats.arrow.Arrow`/`Compose`/`Category` instance), **compositions**
of arrows, **abstract type members**, and **modules** (`trait`/`case class`)
that hold only those two things. It is diagnostic-only — it never rewrites
code, only reports violations — because there is no safe automatic rewrite
from arbitrary logic into arrow form.

Unlike this project's other rules, it does not migrate code toward a style;
it is a boundary/gate that keeps already-conforming code from drifting, on
whichever packages/directories a team opts in.

## Scope configuration

```hocon
RequireArrowArchitecture {
  severity = warning   // or error
  packages = [
    "com.foo.wiring.**"
  ]
  paths = [
    "core/src/architecture/**"
  ]
}
```

A file is in scope if it matches `packages` OR `paths`. Both empty (the
default) means the rule matches nothing — no accidental whole-project scans.
Module-level scoping falls out of the existing setup for free: a Mill module
opts in simply by listing this rule in its own `.scalafix.conf`; `packages`/
`paths` give finer-grained scoping *within* a module for teams that don't
want to scope by a whole package.

`severity` defaults to `warning`, matching `docs/RULES.md`'s convention that
diagnostics default to `LintSeverity.Warning`; a team can promote it to
`error` per-module once a package is fully conformant.

## Allowed-construct grammar

Everything in an in-scope file that isn't one of the following is a
violation.

### Arrow types

A member's declared type is an arrow if it is `A => B`, `Kleisli[F, A, B]`
(including `Reader = Kleisli[Id, -, +]`), or any type for which the semantic
index resolves a `cats.arrow.Arrow`, `Compose`, or `Category` instance.
Structural binary type constructors (`T[A, B]`) with no such instance do
**not** qualify — a hand-rolled wrapper needs a real instance defined to be
treated as an arrow. This keeps the set closed and semantically grounded
rather than shape-guessed.

Arrow members must be **monomorphic**: `def step[G[_]: Sync]: Kleisli[G, A, B]`
or `def transform[T]: T => T` are not allowed, even though their result type
is an arrow. Only plain, non-generic arrow-typed members qualify. (This is a
narrower rule than elsewhere in the codebase, where type parameters are
generally fine — here it's deliberate: genericity over the arrow's own shape
is a step toward "logic", not wiring.)

### Traits / abstract classes

May declare only:

- abstract `type` members,
- abstract `val`/`def` members of (monomorphic) arrow type,
- `extends`/`with` of a supertype that is itself **conforming**: either it
  contributes zero members (a marker/tag trait — e.g. `Serializable`,
  `Product`), or every member it declares independently satisfies this same
  grammar (checked structurally via the semantic index, not by whether the
  supertype happens to live in the configured scope — a supertype built on
  the same principle is fine wherever it lives). When the supertype's shape
  cannot be determined (e.g. a binary dependency with hidden bodies and
  non-abstract members), decline with a diagnostic rather than guess; a team
  hitting this in practice should narrow the rule to run per-module so most
  supertypes stay locally checkable.

### Case classes

Constructor params may only be arrow-typed (monomorphic) or abstract types —
no plain data params (`String`, `Int`, `Boolean`, ...), even config-flavored
ones. A case class may also declare extra `val`/`def` members; those are held
to the composition-expression rule below, same as an object's.

### Objects

May contain only `val`/`def` members that satisfy the composition-expression
rule below — no other member kinds.

### Composition expressions

The only allowed body for a non-abstract `val`/`def` member (in a case class
or object; traits have no bodies at all here). A composition expression is:

- a bare reference to an in-scope arrow-typed identifier — a param, a `val`,
  an abstract member, or an eta-expanded reference to another arrow-typed
  `def`,
- a combinator application of one such expression against another, limited
  to a fixed set: `andThen`, `compose`, `>>>`, `<<<`, `first`, `second`,
  `split`, `&&&`, `|||`, `id`,
- a block of one or more local `val`s (each itself a valid composition
  expression, naming an intermediate step) followed by exactly one final
  composition expression. This is purely for readability of long chains; it
  does not widen what's allowed inside each local `val`.
- a call to a method on a resolved `Arrow`/`Compose`/`Category`/
  `ArrowChoice`-family instance, summoned abstractly (via a context bound,
  `implicitly`, or a `given`) rather than named concretely — e.g.
  `Arrow[F].lift(g)`, `F.first(step)`. This is how one arrow type converts
  to or combines with another, and it keeps architecture code from ever
  naming a concrete arrow implementation. A call to a conversion
  constructor tied to one concrete implementation (`Kleisli.liftF`,
  `Kleisli.local`, etc.) is **not** allowed for the same reason: it leaks a
  concrete type into code that's meant to stay abstract over which arrow it
  uses.

Explicitly **not** allowed anywhere in a composition expression: a lambda
literal with a body (`x => f(g(x)) + 1`), `if`/`match`/`for`/`while`, `var`,
direct side-effecting calls, or any other plain-data logic. The rule's
premise is that leaf-level arrows are *implemented* elsewhere (outside the
scoped architecture packages); this module only *wires* already-existing
arrows together.

### Companion-object constructors

A companion object of an in-scope trait or case class may additionally
declare `def`s that are generic and carry context-bound typeclass evidence
(e.g. `def make[F[_]: Sync](s1: Kleisli[F, A, B], s2: Kleisli[F, B, C]):
Pipeline[F] = Pipeline(s1 andThen s2)`) — genericity is allowed here
specifically because such a `def` assembles a module or an arrow, it is not
itself a fixed arrow-typed member. Its body is still a composition
expression per the grammar above, and its return type must be either the
enclosing module type or an arrow type. This exists so that assembling a
module needing extra evidence (beyond what a bare `apply` call provides)
doesn't force loosening the monomorphic-member rule for traits/case
classes themselves. Outside of a companion object, a generic/evidence-
carrying `def` is still a violation — a plain (non-companion) object stays
restricted to the plain composition-expression rule.

### Imports

Unrestricted — a tooling concern, not an architectural one.

## Diagnostics

One `LintSeverity` diagnostic per violating declaration, anchored on the
member/definition whose shape breaks (not per sub-expression token), per
`docs/RULES.md`'s "report at the granularity of the decision, not of the
evidence." Message names which grammar rule was violated and what was found,
e.g.:

> member `run` has non-arrow type `String`; only arrow types
> (`Function1`, `Kleisli`, or a type with a resolvable `Arrow`/`Compose`/
> `Category` instance) or abstract types are allowed here

## Examples

### Conforming

```scala
package com.foo.wiring

import cats.arrow.Arrow
import cats.data.Kleisli
import cats.effect.Sync

// interface: abstract type + abstract arrow members only
trait Pipeline[F[_]] {
  type Error
  def validate: Kleisli[F, Int, Either[Error, Int]]
  def handle: Kleisli[F, Int, Int]
}

// holder: concrete arrow members only, no plain data params
final case class LivePipeline[F[_]](
  validateStep: Kleisli[F, Int, Either[String, Int]],
  handleStep: Kleisli[F, Int, Int]
) extends Pipeline[F] {
  type Error = String
  def validate: Kleisli[F, Int, Either[Error, Int]] = validateStep
  def handle: Kleisli[F, Int, Int] = handleStep
}

object LivePipeline {
  // companion smart constructor: generic + evidence allowed here only.
  // Composes a lifted Function1 (via the resolved Arrow instance, not a
  // concrete Kleisli constructor) with an existing Kleisli step.
  def make[F[_]: Sync](
    normalize: Int => Int, // a plain Function1 is itself an arrow
    validateStep: Kleisli[F, Int, Either[String, Int]],
    handleStep: Kleisli[F, Int, Int]
  ): LivePipeline[F] = {
    val normalized = Arrow[Kleisli[F, *, *]].lift(normalize) andThen validateStep
    LivePipeline(handleStep = handleStep, validateStep = normalized)
  }
}

// object holding only composition expressions — no type params, no
// evidence, just wiring together already-built, already-typed arrows
object PipelineWiring {
  def combined[F[_]](p: Pipeline[F]): Kleisli[F, Int, Int] =
    p.handle
}
```

### Violating

```scala
package com.foo.wiring

import cats.data.Kleisli
import cats.effect.Sync

trait BadInterface {
  def name: String                           // ✗ non-arrow member type
  def step[G[_]: Sync]: Kleisli[G, Int, Int] // ✗ generic arrow member
}

final case class BadHolder(
  retries: Int,                              // ✗ plain data param
  step: Int => Int
) {
  var cache: Map[Int, Int] = Map.empty       // ✗ var

  def run(x: Int): Int =
    if (cache.contains(x)) cache(x) else step(x) // ✗ if, direct logic

  def compute: Int => Int =
    x => step(x) + 1                         // ✗ lambda literal with a body
}

object BadHolder {
  def liftedUnit[F[_]: Sync]: Kleisli[F, Int, Int] =
    Kleisli.liftF(Sync[F].unit).map(_ => identity[Int]) // ✗ names a concrete
    // implementation constructor (Kleisli.liftF) instead of going through a
    // resolved Arrow/Compose instance abstractly
}

object PlainWiring {
  def make[F[_]: Sync](s: Kleisli[F, Int, Int]): Kleisli[F, Int, Int] = s
  // ✗ generic + evidence in a plain (non-companion) object — this exact
  // def would be fine inside LivePipeline's companion object above
}
```

## Implementation approach

Single-pass structural walk (over the two-pass project-wide-closure approach
used by `KleisliLiftScope`/`WidenScope`): for each in-scope template
(`trait`/`class`/`case class`/`object`), classify every member declaration
and every expression against the grammar above, using per-file semantic
resolution (`resolve_implicits`/instance lookup) to decide arrow-ness. No
cross-file/project-wide state is needed, because a referenced symbol's
arrow-ness is decided from its own declared signature, not derived
transitively — the same reason `KleisliLiftScope`'s two-pass design exists
for signature-*changing* rules doesn't apply to a rule that only *reports*.

## Registration & testing

- New file `scalafix/src/fix/RequireArrowArchitecture.scala`.
- Registered in `scalafix/resources/META-INF/services/scalafix.v1.Rule`
  alongside the existing rules.
- Executed fixtures under `scalafix/testInput`/`testOutput`, per
  `docs/GOLDEN_FIXTURES.md` — for a diagnostic-only rule this means fixtures
  that assert the emitted lint messages/positions (input and output source
  are identical, since nothing is rewritten).
- Cases to cover: a conforming trait/case-class/object; each grammar
  violation individually (non-arrow member, generic arrow member, `var`,
  `if`/`match` in a composition body, plain data param); an out-of-scope
  file with the same violations (must produce no diagnostics); inheritance
  from a conforming marker trait and from a conforming same-principle trait
  outside the configured scope; inheritance from a non-conforming supertype
  whose shape can't be determined; a companion-object smart constructor
  with a generic type param and context-bound evidence (conforming) and
  the same generic/evidence `def` placed outside a companion object
  (violation); an abstract-instance conversion call (`Arrow[F].lift(...)`,
  conforming) versus a concrete-implementation conversion call
  (`Kleisli.liftF(...)`, violation).

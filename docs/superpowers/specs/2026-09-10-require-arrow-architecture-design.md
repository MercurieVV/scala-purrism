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

Explicitly **not** allowed anywhere in a composition expression: a lambda
literal with a body (`x => f(g(x)) + 1`), `if`/`match`/`for`/`while`, `var`,
direct side-effecting calls, or any other plain-data logic. The rule's
premise is that leaf-level arrows are *implemented* elsewhere (outside the
scoped architecture packages); this module only *wires* already-existing
arrows together.

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
  whose shape can't be determined.

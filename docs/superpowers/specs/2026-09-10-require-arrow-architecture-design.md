# RequireArrowArchitecture — Design

## Purpose

A new, standalone scalafix lint rule that enforces a restricted architectural
style on selected packages/directories: definitions there may only express
themselves in terms of **abstract arrow slots** (a binary type parameter or
abstract type member bounded by `cats.arrow.Arrow`/`Compose`/`Category` or a
subtypeclass of one), **compositions** of values of that slot, plain
**abstract type members**, and **modules** (`trait`/`case class`) that hold
only those things. No concrete arrow implementation (`Function1`, `Kleisli`,
or any other named type) may ever be written inside a scoped file — the
whole point is that this code stays polymorphic over *which* arrow it uses.
It is diagnostic-only — it never rewrites code, only reports violations —
because there is no safe automatic rewrite from arbitrary logic into arrow
form.

Unlike this project's other rules, it does not migrate code toward a style;
it is a boundary/gate that keeps already-conforming code from drifting, on
whichever packages/directories a team opts in. A concrete arrow
(`Kleisli[IO, *, *]`, say) only ever gets named at the composition root
*outside* the scoped packages, where the abstract slot is finally
instantiated.

## Scope configuration

```hocon
RequireArrowArchitecture {
  severity = warning   // or error
  maxArrowConversions = 1
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

`maxArrowConversions` (default `1`) caps how many *distinct*
`ArrowConvert[P, Q]` requirements — counted by their `(P, Q)` type pair, not
by call site — a trait/case-class and its companion object may together
require. See Cross-slot conversion below for what's counted and why it's
combined across template and companion; exceeding it reuses the same
`severity` setting rather than a separate knob.

## Allowed-construct grammar

Everything in an in-scope file that isn't one of the following is a
violation.

### Arrow slots

An **arrow slot** is a binary type (kind `(*, *) => *`) that is abstract at
the point it's used — never a named concrete type — and is bounded or
required to have an instance of `cats.arrow.Arrow`, `Compose`, `Category`,
or a subtypeclass of one of those. It's declared one of two ways (both
allowed, pick whichever fits):

- a **type parameter with a context bound** on the enclosing
  trait/case class/def: `trait Pipeline[Step[_, _]: Arrow]`,
- an **abstract type member with a separate instance requirement**:
  `trait Pipeline { type Step[_, _]; given Arrow[Step] }` (or an equivalent
  context-bound/implicit-parameter form requiring `Arrow[Step]`).

A member's declared type qualifies as an arrow only if it is that slot
applied to two concrete-or-abstract types, e.g. `Step[Int, Either[Error,
Int]]`. **Naming a concrete arrow implementation directly — `Int => Int`,
`Kleisli[F, A, B]`, or any other type with its own name rather than the
slot's — is a violation**, even though such a type would itself satisfy
`Arrow`/`Compose`/`Category`. The rule doesn't care whether a type is
*capable* of being an arrow; it cares whether the scoped code ever commits
to *which* one it is, and naming one concretely is exactly that commitment.

A structural binary type constructor used as a slot must carry a real
`Arrow`/`Compose`/`Category`(-family) bound — a bare `T[_, _]` with no such
bound doesn't qualify, for the same reason as before: closed and
semantically grounded, not shape-guessed.

Arrow slot members must be **monomorphic** in every type parameter other
than the slot's own two holes: `def step[G[_]: Sync]: Step[G[A], B]` is not
allowed. Only plain, non-generic (beyond the slot itself) arrow-typed
members qualify — genericity beyond the slot is a step toward "logic", not
wiring.

### Cross-slot conversion

Converting a value from one arrow slot to a different one (`Step1` to
`Step2`) needs evidence, since Cats has no built-in typeclass for
"natural transformation between two binary arrows." This rule's own
vocabulary supplies one, itself fully abstract and single-method:

```scala
trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}
```

A composition expression may call `.apply` on a value of this typeclass
(summoned via context bound or `given`, never a concretely-constructed
instance) to move between slots. Where the two slots are actually the same
type at the instantiation site, this is a no-op; the scoped code never
knows or cares.

`ArrowConvert` itself is a **special-cased shape** in the trait grammar, not
a general opening for per-method type parameters: the rule recognizes
exactly this pattern — a single abstract method whose only type parameters
(`A`, `B`) are consumed by applying the trait's own two slot type
parameters (`P`, `A`, `B` → `P[A, B]`; likewise for `Q`) — and no other. A
trait declaring any other per-method-generic abstract method is still a
violation; this carve-out exists solely so cross-slot conversion has
somewhere to live without loosening the monomorphic-member rule generally.

**Conversion budget.** `maxArrowConversions` counts the number of
*distinct* `(P, Q)` pairs for which a trait/case-class and its companion
object, taken together, require `ArrowConvert[P, Q]` evidence — as a
context bound or `given`/implicit parameter anywhere in that pair of
templates. Requiring `ArrowConvert[Step1, Step2]` in two different
companion-object constructors still counts once; requiring both
`ArrowConvert[Step1, Step2]` and `ArrowConvert[Step2, Step3]` counts as two.
Exceeding the configured max (default `1`) is reported once per
trait/case-class pair, at the `severity` configured for the rule as a
whole. The premise: bridging exactly two slots at one boundary is ordinary
wiring; a module quietly accumulating several such bridges is usually
doing real translation logic dressed up as composition.

### Traits / abstract classes

May declare only:

- abstract `type` members (including an arrow-slot type member, per above),
- abstract `val`/`def` members of (monomorphic) arrow-slot type,
- type parameters bounded by `Arrow`/`Compose`/`Category` (arrow slots) or
  otherwise unconstrained (ordinary type parameters used inside slot
  applications, e.g. the `A`/`B` in `Step[A, B]`),
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

Constructor params may only be arrow-slot-typed (monomorphic) or abstract
types — no plain data params (`String`, `Int`, `Boolean`, ...), even
config-flavored ones, and no concretely-named arrow type either (see Arrow
slots above). A case class may also declare extra `val`/`def` members;
those are held to the composition-expression rule below, same as an
object's.

### Objects

May contain only `val`/`def` members that satisfy the composition-expression
rule below — no other member kinds. A `def` here may be generic in an arrow
slot (e.g. `def combined[Step[_, _]](p: Pipeline[Step]): Step[Int, Int] =
p.handle`), but may **not** carry a typeclass bound/evidence requirement on
that slot (no `: Arrow`, no `given` parameter) — plain genericity that only
forwards or selects an already-existing value is fine; genericity that
needs evidence to actually *compose* something is reserved for
companion-object constructors (see below), because that's where the
grammar allows the resulting evidence-bearing call in the first place.

### Composition expressions

The only allowed body for a non-abstract `val`/`def` member (in a case class
or object; traits have no bodies at all here). A composition expression is:

- a bare reference to an in-scope arrow-slot-typed identifier — a param, a
  `val`, an abstract member, or an eta-expanded reference to another
  arrow-slot-typed `def`,
- a combinator application of one such expression against another, limited
  to a fixed set resolved on the slot's `Arrow`/`Compose`/`Category`(-family)
  instance: `andThen`, `compose`, `>>>`, `<<<`, `first`, `second`, `split`,
  `&&&`, `|||`, `id`,
- a block of one or more local `val`s (each itself a valid composition
  expression, naming an intermediate step) followed by exactly one final
  composition expression. This is purely for readability of long chains; it
  does not widen what's allowed inside each local `val`,
- a call to `.apply` on an `ArrowConvert[P, Q]` instance, summoned
  abstractly, to move a value from one arrow slot to another (see
  Cross-slot conversion above).

Explicitly **not** allowed anywhere in a composition expression: naming a
concrete arrow type, a lambda literal with a body (`x => f(g(x)) + 1`),
`if`/`match`/`for`/`while`, `var`, direct side-effecting calls, or any other
plain-data logic. The rule's premise is that leaf-level arrows are
*implemented*, and concrete arrow types are only ever *named*, outside the
scoped architecture packages; this module only *wires* already-existing,
still-abstract arrows together.

### Companion-object constructors

A companion object of an in-scope trait or case class may additionally
declare `def`s that are generic and carry context-bound typeclass evidence
— most commonly exactly the `Arrow`/`Compose`/`Category` bound an arrow slot
itself needs, e.g.:

```scala
def make[Step[_, _]: Arrow](
  normalize: Step[Int, Int],
  validateStep: Step[Int, Either[String, Int]]
): Pipeline[Step] =
  Pipeline(normalize andThen validateStep)
```

Genericity is allowed here specifically because such a `def` assembles a
module or an arrow value, it is not itself a fixed arrow-slot-typed member.
Its body is still a composition expression per the grammar above, and its
return type must be either the enclosing module type or an arrow-slot type.
This exists so that assembling a module needing extra evidence (beyond what
a bare `apply` call provides) doesn't force loosening the monomorphic-member
rule for traits/case classes themselves. Outside of a companion object, a
generic/evidence-carrying `def` is still a violation — a plain
(non-companion) object stays restricted to the plain composition-expression
rule with no type parameters at all.

### Imports

Unrestricted — a tooling concern, not an architectural one. (Importing
`cats.data.Kleisli` to instantiate a slot happens outside scope; nothing
stops the import itself from appearing in a scoped file, only *naming* the
type in a declaration does.)

## Diagnostics

One `LintSeverity` diagnostic per violating declaration, anchored on the
member/definition whose shape breaks (not per sub-expression token), per
`docs/RULES.md`'s "report at the granularity of the decision, not of the
evidence." Message names which grammar rule was violated and what was found,
e.g.:

> member `run` names a concrete type `Kleisli[F, Int, Int]`; only an
> abstract arrow slot (a type parameter or type member bounded by
> `Arrow`/`Compose`/`Category`) applied to two types is allowed here

## Examples

### Conforming

```scala
package com.foo.wiring

import cats.arrow.Arrow

trait ArrowConvert[P[_, _], Q[_, _]] {
  def apply[A, B](p: P[A, B]): Q[A, B]
}

// interface: abstract type + abstract arrow-slot members only
trait Pipeline[Step[_, _]: Arrow] {
  type Error
  def validate: Step[Int, Either[Error, Int]]
  def handle: Step[Int, Int]
}

// holder: concrete arrow-slot members only, no plain data params, no
// concretely-named arrow type anywhere
final case class LivePipeline[Step[_, _]: Arrow](
  validateStep: Step[Int, Either[String, Int]],
  handleStep: Step[Int, Int]
) extends Pipeline[Step] {
  type Error = String
  def validate: Step[Int, Either[Error, Int]] = validateStep
  def handle: Step[Int, Int] = handleStep
}

object LivePipeline {
  // companion smart constructor: generic + evidence (the slot's own Arrow
  // bound) allowed here only
  def make[Step[_, _]: Arrow](
    normalize: Step[Int, Int],
    validateStep: Step[Int, Either[String, Int]],
    handleStep: Step[Int, Int]
  ): LivePipeline[Step] = {
    val normalized = normalize andThen validateStep
    LivePipeline(handleStep = handleStep, validateStep = normalized)
  }
}

// object holding only composition expressions — no type params, no
// evidence, just wiring together already-abstract, already-typed arrows
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
import cats.effect.Sync

trait BadInterface[Step[_, _]: Arrow] {
  def name: String                    // ✗ non-arrow member type
  def step[G[_]: Sync]: Step[G[Int], Int] // ✗ generic beyond the slot's own holes
}

final case class BadHolder[Step[_, _]: Arrow](
  retries: Int,                       // ✗ plain data param
  step: Step[Int, Int]
) {
  var cache: Map[Int, Int] = Map.empty // ✗ var

  def run(x: Int): Int =
    if (cache.contains(x)) cache(x) else x // ✗ if, direct logic

  def compute: Step[Int, Int] =
    step andThen step // this line alone would be fine; shown only for context
}

final case class NamesConcreteArrow(
  step: Kleisli[cats.effect.IO, Int, Int] // ✗ names a concrete arrow type
                                            // directly instead of using an
                                            // abstract slot
) {}

object PlainWiring {
  def make[Step[_, _]: Arrow](s: Step[Int, Int]): Step[Int, Int] = s
  // ✗ carries an Arrow evidence bound in a plain (non-companion) object —
  // being generic in Step alone would be fine (see PipelineWiring.combined
  // above), but the evidence bound is reserved for companion-object
  // constructors; this exact def would be fine inside LivePipeline's
  // companion object above
}
```

## Implementation approach

Single-pass structural walk (over the two-pass project-wide-closure approach
used by `KleisliLiftScope`/`WidenScope`): for each in-scope template
(`trait`/`class`/`case class`/`object`), classify every member declaration
and every expression against the grammar above, using per-file semantic
resolution to decide (a) whether a type is an abstract arrow slot with a
resolvable `Arrow`/`Compose`/`Category`(-family) bound, and (b) whether a
member's declared type names a concrete type instead of applying that slot.
No cross-file/project-wide state is needed, because a referenced symbol's
slot-ness is decided from its own declared signature, not derived
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
- Cases to cover: a conforming trait/case-class/object built on an abstract
  arrow slot (both the type-parameter and abstract-type-member declaration
  forms); each grammar violation individually (non-arrow member, generic
  member beyond the slot's holes, `var`, `if`/`match` in a composition body,
  plain data param, a member naming a concrete arrow type like `Kleisli`
  directly); an out-of-scope file with the same violations (must produce no
  diagnostics); inheritance from a conforming marker trait and from a
  conforming same-principle trait outside the configured scope; inheritance
  from a non-conforming supertype whose shape can't be determined; a
  companion-object smart constructor with a generic slot type param and its
  `Arrow` bound (conforming) and the same generic/evidence `def` placed
  outside a companion object (violation); a plain object `def` generic in
  an arrow slot with no evidence bound, just forwarding a value
  (conforming) versus the same shape with an `Arrow` bound added
  (violation); an `ArrowConvert[P, Q].apply` call moving between two slots
  (conforming) versus a hand-written conversion that names a concrete
  arrow type to bridge them (violation); a class/companion pair requiring
  exactly `maxArrowConversions` distinct `(P, Q)` pairs (conforming), one
  more than the configured max (violation), and the same `(P, Q)` pair
  required twice across template and companion counting once, not twice.

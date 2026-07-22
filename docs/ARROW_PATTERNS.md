# Kleisli → Arrow rewrite catalogue (`PreferArrow`)

## Status: v2 engine (arrow-IR compiler)

The original catalogue below was the specification for three hand-written
syntactic matchers. Those matchers fired on **zero** sites in the reference
corpus `gh-tasks-llm-executor`, because four of their five gates sat upstream of
pattern matching (return type spelled literally `F[…]`; no type parameters
allowed; body an un-wrapped `for`/`flatMap`; Kleisli identity by the token
`Kleisli`). They were replaced by a small arrow-IR compiler
(`scalafix/src/fix/arrow/`): parse a monadic body into `ArrowIR`, normalize to a
fixpoint, gate on a readability budget, render `>>>`/`&&&`/`|||`/`.map`/`.local`.

What ships now:

| Shape | Status | Notes |
|---|---|---|
| A — linear chain (n≥2) → `>>>` | **shipped** | `for` and desugared `flatMap` forms; renders `>>>` (a `Compose` op), not `andThen`. |
| B — `k.run(x).map(f)` → `k.map(f)` | **shipped** | Declines when `f` captures the input. |
| C — fan-out → `&&&`, **any arity** | **shipped** | Arity ≥3 nests as `((a, b), c)` with a flattening `.map`; the arity-2 cap of the old matcher is gone. Branches may read *projections* of the input (`k.run(x.field)` → `k.local(x => x.field)`) and multi-argument **auto-tupled** applications (`k(a, b)` → `k.local(x => (a, b))`). Binding identity is by SemanticDB symbol; the shadowed-input near-miss still warns. |
| D — ask-merge (`Kleisli.ask &&& k`) | **not shipped** | The IR (`Merge(Id, _)`) exists but the parser does not yet produce it; it is also the shape the readability budget most often declines. |
| E — `Either` branch → `\|\|\|` | **shipped (clean sub-shape)** | `k.run(x).flatMap { case Left(l) => kl.run(l); case Right(r) => kr.run(r) }` → `k >>> (kl \|\|\| kr)`. Restricted to an `Either` scrutinee with two arms, each a lone Kleisli application of the matched value; a guard, a third case, or a non-Kleisli arm is declined. `Option`/`Boolean` scrutinees are not yet lifted to `Either`. |
| F, G | **dropped** | Unchanged from the verdicts below. |

Two entry points feed one engine: **body-only** (rewrite the interior of a
`Kleisli { x => … }` in place, signature untouched — the entry that reaches the
idiomatic corpus) and **signature-lifting** (`def m(x: A): F[B]` →
`def m: Kleisli[F, A, B]`). Kleisli identity is `fix.arrow.KleisliType`, which
dealiases through `-->`, `Flow`, fully-qualified and inferred types via
SemanticDB — and rejects `-->` used as an abstract type parameter.

The n=2 `composition` path was deleted from `PreferKleisli` (it duplicated
`PreferArrow` and could double-patch the same span under the umbrella rule);
`PreferKleisli.kleisliApplyRewrite` now steps aside for single-parameter
composition-shaped bodies via `leaveToPreferArrow`, so the two rules never emit
overlapping patches. Verified by `golden/ArrowUmbrellaNoConflict.scala`.

---

## Original catalogue (historical)

Specification only. No rule code and no fixtures ship with this document; the
subtasks under #4 read the shipped set from here.

## Verdicts

| # | Pattern | Verdict | Reasoning |
|---|---------|---------|-----------|
| A | n-step (n≥2) `flatMap` / `for` chain → `k1.andThen(k2).andThen(k3)` | **ship** | Generalises the single existing emission site (`TypelevelPurrism.scala:1363`), which today handles only n=2. Highest value per unit of risk: the shape is purely linear, needs no effect-ordering argument (it *is* the `flatMap` sequencing), and subsumes the code already in the tree. |
| B | `k.run(x).map(f)` → `k.map(f).run(x)` / `k.map(f)` | **ship** | Mechanical and local. `Kleisli.map` is defined as `Kleisli(a => F.map(run(a))(f))`, so the rewrite is an equality, not an approximation. Cheap to implement, cheap to review, and it is what makes A's chains stay point-free when a pure step sits between two effectful ones. |
| C | fan-out `for { b <- k1.run(x); c <- k2.run(x) } yield (b, c)` → `k1 &&& k2` | **ship** | Real shape in Kleisli-heavy code, and the effect ordering is provably preserved (see below). Requires SemanticDB for binding identity, which is the main cost. Third and last shipped pattern. |
| D | later step needs input *and* intermediate → `(Kleisli.ask &&& k1).andThen(k2)` | **defer** | The output is markedly less readable than the input, and the `.first` / `.local` alternative produces a different shape again, so there is no single right answer to encode. **Would change the verdict:** three or more instances of the shape found in a real consuming codebase (`gh-tasks-llm-executor` is the reference corpus, as `KleisliFlow.scala` already mirrors it), plus agreement on one target form. |
| E | `Either` / `Option` branching → `ArrowChoice` (`choice`, `\|\|\|`, `.left`, `.right`) | **defer** | The only pattern that justifies `ArrowChoice` at all, but the detection surface is large: `Either` in the effect (`EitherT`, `F[Either[..]]`), `flatMap` on the `Either` itself, `fold`, `match` on `Case` trees — each a distinct predicate. Shipping it alongside A–C would break the 2–4 budget and the "small enough to review" criterion. **Would change the verdict:** A–C landed and green, plus a decision to restrict E to exactly one sub-shape (recommended first cut: `k1.andThen(Kleisli(_.fold(l, r)))` where both branches are Kleislis → `k1.andThen(kl \|\|\| kr)`). |
| F | `Function1` `andThen` / `compose` → `>>>` / `<<<` | **drop** | Cosmetic. `f andThen g` is already point-free and already readable; `>>>` swaps one symbol for another and buys no compositional power. It also fires on every `Function1` in the codebase, which is a large blast radius for zero semantic gain. **Would change the verdict:** a request to normalise mixed `andThen`/`>>>` style within one file, which is a formatting concern and belongs in scalafmt or a lint, not a rewrite. |
| G | generalise `Kleisli[F, A, B]` signatures to abstract `Arrow[=>:]` | **drop** | Out of scope as stated, and dropping outright rather than warning. A `LintSeverity.Warning` here would fire on every Kleisli-typed member in a codebase that deliberately chose Kleisli, with no automatic fix and no local way to tell whether abstraction is wanted. Per `docs/RULES.md` an over-severe diagnostic is actively harmful (Scalafix withholds a rule's patches when it reports lint errors), and even a warning-level version is noise-per-signature with no actionable next step. **Would change the verdict:** nothing local; this needs whole-program knowledge of every call site, which a single-file Scalafix rule does not have. |

Shipped set: **A, B, C** (three patterns, inside the 2–4 budget).

## Decision: the existing 2-step `composition` case

`TypelevelPurrism.scala:1363` — `CompositionRewrite(s"$first.andThen($second)")`,
reached from `composition` via `kleisliCompositionRewrite` — **migrates into
`PreferArrow`** and is deleted from `PreferKleisli`.

Rationale: A is a strict generalisation of that case. Leaving it in place means
two rules can emit an `andThen` for the same source span, and Scalafix has no
ordering guarantee between rules in one run, so the overlapping patches are a
conflict waiting to happen. `PreferKleisli` keeps *introduction* of `Kleisli`
(`kleisliAliasRewrite`, `kleisliApplyRewrite`, `tupledCallPatches`,
`sequencedLocalCompositionRewrites`); `PreferArrow` owns *composition of
existing* Kleislis.

This is a behaviour change and is fixture-covered in two steps:

1. **Before the move**, add an executed characterization fixture under
   `scalafix/testInput/src` + `testOutput/src` with `rules = [PreferKleisli]`
   pinning today's n=2 output. `PreferKleisli` currently has no executed
   fixture at all (`golden/KleisliFlow.scala` names `DisableSyntax` and only
   feeds `GraphBuilderSuite`), so "existing behaviour stays green" is unproven
   until this exists. Generate it with `SCALAFIX_SAVE_EXPECT=true` and read the
   diff — it records what the rule *does*, which may not be what it *should* do.
2. **With the move**, retarget that fixture's header to
   `rules = [PreferArrow]`. If the produced text is identical, the migration is
   behaviour-preserving; any diff is the real behaviour change and must be
   justified in the PR.

## Shipped patterns

### A — linear chain → `andThen`

Before:

```scala
def enrich(id: UserId): F[Report] =
  loadUser.run(id).flatMap(user => loadOrders.run(user).flatMap(orders => summarise.run(orders)))

def enrichFor(id: UserId): F[Report] =
  for {
    user   <- loadUser.run(id)
    orders <- loadOrders.run(user)
    report <- summarise.run(orders)
  } yield report
```

After (both forms):

```scala
def enrich: Kleisli[F, UserId, Report] =
  loadUser.andThen(loadOrders).andThen(summarise)
```

**Detection.** Two entry shapes, normalised to one list of steps before
emission:

- Desugared: `Term.Apply(Term.Select(qual, Term.Name("flatMap")), Term.ArgClause(List(f: Term.Function), _))`, recursing into `f.body`. This is the existing `composition` matcher generalised from a fixed pair to a fold.
- `for`-comprehension: `Term.ForYield(enums, yieldTerm)` where every enum is an
  `Enumerator.Generator(Pat.Var(v), rhs)` — reject `Enumerator.Val` and
  `Enumerator.Guard` outright — and `yieldTerm` is `Term.Name` equal to the last
  generator's pattern. A `yield` that is anything else is pattern B territory,
  not A.

Each step's `rhs` must be `Term.Apply(Term.Select(k, Term.Name("run" | "apply")), Term.ArgClause(List(arg), _))`, or a bare `Term.Apply(k, List(arg))` where `k` is Kleisli-typed. Step *i>0*'s `arg` must be exactly the `Term.Name` bound by step *i-1*; step 0's `arg` must be the enclosing `def`'s single parameter.

**Preconditions.**

1. Every callee resolves to `cats/data/Kleisli#` with a compatible `F`.
2. Each intermediate binding is used exactly once, as the sole argument of the
   next step. Any other use of `user` in the body kills the rewrite — it is no
   longer a pure pipe.
3. No `Enumerator.Guard`, no nested `Term.Block` statements between steps, no
   binding shadowing another in scope.
4. The enclosing `def` has exactly one plain parameter and a declared return
   type `F[R]` (reuse `isFResult` / `singlePlainParameter`), or a declared
   `Kleisli[...]` return type for the already-point-free body form.

**Symbols vs names.** **SemanticDB required.** Precondition 2 and 3 are binding
identity questions, and `collectKleisliNames` is name-based, which conflicts
with `docs/RULES.md` ("Identifier names are not identity"). `PreferArrow` must
resolve each callee's symbol via `doc.info` / `Symbol` rather than matching a
`Set[String]`. Do not extend `collectKleisliNames`.

**Sketch.** Add `def steps(body: Term, input: Term.Name): Option[List[String]]`
that unfolds either entry shape into a `List[KleisliStep(callee, argument,
binding)]`. Validate the chain with a single left fold checking that each
`argument` is the previous `binding`. Emit
`steps.map(_.callee).mkString(".andThen(") + ")" * (n - 1)`, or build it with a
`reduceLeft`. Reuse the existing `renderModifiers` / signature rewriting from
`kleisliCompositionRewrite` for the `def` header. n=2 falls out of the same
fold, which is what makes the migration above a no-op on output.

### B — `map` after `run`

Before:

```scala
def name(id: UserId): F[String] =
  loadUser.run(id).map(_.name)
```

After:

```scala
def name: Kleisli[F, UserId, String] =
  loadUser.map(_.name)
```

**Detection.** `Term.Apply(Term.Select(inner, Term.Name("map")), Term.ArgClause(List(f), _))` where `inner` matches the same "Kleisli applied to the def's parameter" shape as an A step. Also fires as the terminal element of an A chain, where the `for`'s `yield` is `Term.Name("f")` applied to the last binding rather than the binding itself — in that position, emit `.map(...)` after the final `.andThen(...)`.

**Preconditions.**

1. `inner`'s callee resolves to `Kleisli`; the `.map` must be the one on `F`,
   not one already on a `Kleisli` (otherwise the rule is a no-op that still
   rewrites, breaking idempotency).
2. `f` is a single-argument function or placeholder that does not mention the
   def's input parameter. If it does, the rewrite drops a capture and is wrong.
3. Same single-plain-parameter / `F[R]` return-type shape as A.

**Symbols vs names.** **SemanticDB required**, for precondition 1 only: telling
`F.map` from `Kleisli.map` is a type question, and getting it wrong makes the
rule non-idempotent, which `docs/RULES.md` forbids. The syntactic match itself
is name-independent.

**Sketch.** A small `mapAfterRun` matcher sharing A's step extractor. Once A's
extractor returns `KleisliStep`, B is "a step whose result is consumed by
exactly one `map`". Implement B *inside* A's emitter rather than as a separate
traversal, so the two never produce overlapping patches on the same span.

### C — fan-out → `&&&`

Before:

```scala
def profile(id: UserId): F[(User, Settings)] =
  for {
    user     <- loadUser.run(id)
    settings <- loadSettings.run(id)
  } yield (user, settings)
```

After:

```scala
def profile: Kleisli[F, UserId, (User, Settings)] =
  loadUser &&& loadSettings
```

**Detection.** `Term.ForYield(enums, Term.Tuple(List(Term.Name(a), Term.Name(b))))`
with exactly two `Enumerator.Generator`s, both matching the A step shape, both
arguments being the *same* `Term.Name`, and `(a, b)` being the two generator
patterns in that order. The desugared `flatMap`/`map` form is
`k1.run(x).flatMap(a => k2.run(x).map(b => (a, b)))` — match it too, since
`for` with a non-`yield`-identity body desugars to `map` and A's extractor
already walks that spine.

**Preconditions.**

1. Both `run` arguments are the same *binding*, not merely the same spelling —
   the whole reason C needs symbols.
2. That binding is unshadowed between the two generators and is the enclosing
   `def`'s parameter.
3. Neither generator's callee mentions the other's binding.
4. `Monad[F]` must be available, not merely `FlatMap[F]` — see below. In
   practice the enclosing signature already carries it, but if the context bound
   is `F[_]: FlatMap` the rewrite must not fire.
5. Tuple order matches generator order. A `yield (settings, user)` is *not* `&&&`
   (it is `(k1 &&& k2).map(_.swap)`); do not rewrite it.

**Symbols vs names.** **SemanticDB required**, unconditionally. Precondition 1
is exactly the case `docs/RULES.md` calls out: two unrelated bindings can share
a name, and a name-based match here silently fuses two different inputs into
one.

**Sketch.** Runs after A's extractor, on the same normalised step list, in a
branch taken when two steps share an argument binding rather than chaining. Emit
`s"$k1 &&& $k2"`, parenthesising each callee if it is not a `Term.Name` or
`Term.Select`. Limit the first cut to arity two; three-way fan-out
(`k1 &&& k2 &&& k3` nests as `((A, B), C)`, which does *not* match a flat
`yield (a, b, c)`) is out of scope and must be explicitly rejected, not
silently mis-emitted.

## Effect ordering for C (and D)

The instance is `Kleisli.catsDataArrowChoiceForKleisli[F](implicit F: Monad[F]): ArrowChoice[Kleisli[F, *, *]]` (`cats.data.KleisliInstances`, mixed in via `KleisliArrowChoice extends ArrowChoice[Kleisli[F, *, *]] with CommutativeArrow[...]`). Note the constraint is `Monad[F]`, strictly stronger than the `FlatMap[F]` the original `for`-comprehension needs — hence precondition 4.

`&&&` comes from `Arrow.ArrowOps` and is `Arrow[F].merge(f, g)`, whose default
implementation is:

```scala
def merge[A, B, C](f: F[A, B], g: F[A, C]): F[A, (B, C)] =
  andThen(lift((x: A) => (x, x)), split(f, g))

// Strong / Category defaults
def split[A, B, C, D](f: F[A, B], g: F[C, D]): F[(A, C), (B, D)] =
  andThen(first(f), second(g))
```

`Kleisli`'s `andThen` is `flatMap` under the hood, and `first(f)` is
`Kleisli { case (a, c) => F.map(f.run(a))((_, c)) }`. Substituting, `(k1 &&& k2).run(x)` reduces to
`k1.run(x).flatMap(b => k2.run(x).map(c => (b, c)))` — **the exact desugaring of the
`for`-comprehension in C**, with `k1` sequenced strictly before `k2`.

Consequences:

- **Ordering preserved.** Left-to-right, same as the source.
- **Short-circuiting preserved.** If `F` is `Either`-like / `OptionT` /
  `MonadError`, a failure in `k1` prevents `k2` from running, because the
  reduction is a `flatMap` on `F`. Nothing is made eager or parallel.
- `CommutativeArrow` in the instance's parent list is a claim about the
  *Kleisli* category, not licence to reorder effects — it holds only when `F` is
  a commutative monad, and the rewrite never relies on it.
- **`Parallel` is not involved.** `&&&` is emphatically not `parTupled`; anyone
  reading the output expecting concurrency is mistaken, but the rule is not.
- **D** would additionally need `Kleisli.ask[F, A]`, which requires only
  `Applicative[F]` and performs no effect, so `(Kleisli.ask &&& k1)` runs `k1`
  exactly once in the same position. D's ordering is therefore also sound; its
  deferral is a readability call, not a correctness one.

## Fixture obligations for the shipped set

Per `docs/RULES.md`, every automatic rewrite needs an executed fixture under
`scalafix/testInput` + `scalafix/testOutput` — not `scalafix/test/resources`,
where nothing runs. Minimum:

- The `PreferKleisli` characterization fixture for the n=2 case, added *before*
  the migration.
- A per-pattern fixture for A (both the `flatMap` and `for` entry shapes), B,
  and C.
- One negative fixture per pattern for the precondition that is most likely to
  be violated in real code: A/binding used twice, B/`f` captures the input,
  C/same spelling but different binding. A negative fixture that produces no
  output file is the cheapest way to pin "the rule correctly does nothing".

## Aggressive mode (`PreferArrow.aggressive`, opt-in, off by default)

The conservative budget only fires where point-free is at least as readable as
the source. Real monadic codebases often keep the input around in the `yield`
and call *plain* effectful methods (not existing Kleislis) in each generator —
shapes where the point-free form is *correct* but busier. `PreferArrow.aggressive
= true` opts a project into rewriting those anyway.

```hocon
PreferArrow.aggressive = true
```

What it unlocks, on top of the conservative shapes:

- **Plain-`F` lifting.** A generator `b <- svc.thing(x.a, x.b)` whose right-hand
  side is an ordinary `F`-returning call is lifted in place into
  `Kleisli { (x: T) => svc.thing(x.a, x.b) }`, so independent generators can fan
  out even though none of them was a Kleisli to begin with.
- **Input retention via `Kleisli.ask`.** When the `yield` still references the
  arrow input, a leading `Kleisli.ask[F, A]` carries it alongside the fanned-out
  results, and the trailing `.map` destructures the nested tuple back to the
  original `yield` body.
- **Eta-collapse.** `Kleisli { x => k.run(x) }` reduces to `k`.
- **Arms that ignore the input.** Only *one* generator has to be a function of
  the arrow input; the others may be constant effects (`t <- svc.total`), lifted
  as `Kleisli { (x: T) => svc.total }`. A fan-out where *no* arm reads the input
  is still declined — that is a plain `mapN` over constants with nothing
  arrow-shaped to gain.
- **No identity reshape.** At arity one or two the arms already produce the
  arrow's output directly, so a `yield` naming them in arm order emits no
  trailing `.map`. Arity three and up keeps the destructuring `.map`, since
  `&&&` nests as `((a, b), c)`.
- **Discard generators — `*>` and `flatTap`.** A `_ <- log(...)` is not a
  fan-out arm: its result is thrown away, so tupling it and projecting it back
  out is pure waste. Two positions are recognised:
  - **Leading** (`_ <- ...` before the named generators) renders as
    `Kleisli { (x: T) => log(...) } *> <rest>`. `*>` needs only `Apply[F]` —
    strictly weaker than the `Monad[F]` an `&&&` costs — feeds both sides the
    same input, and preserves the `for`'s order and short-circuiting.
  - **Trailing** (`_ <- ...` after them, reading their results) renders as
    `<arms>.flatTap { case (a, b) => Kleisli.liftF(record(a)) }`. `flatTap`
    discards the *value*, not the effect, so a failure in the tap still fails
    the arrow. The tap uses `Kleisli.liftF` rather than a typed lambda because
    the receiver has already fixed the expected type there; a leading discard
    cannot, since as the left operand of `*>` it is what the input type is
    inferred *from*.

  A discard counts towards the "at least two effects" floor, so a single named
  generator behind a `_ <- log(...)` is enough to fire.

Only independent-generator `for`s qualify: no `val` binder, no guard, and no
generator that reads another's binding (that genuinely needs `flatMap`, which
this path never fakes). Two discard shapes are refused outright:

- a discard *between* two named generators — `&&&` feeds both arms the same
  input and has no position to run an effect in between, so emitting anything
  would mean re-ordering it against a neighbour;
- a trailing discard that reads the *arrow input* — it is rendered inside
  `.flatTap`, where only the arms' results are bound, and threading the input in
  as well costs more plumbing than the `for` it replaces.

Correctness is unchanged — independent generators commute under the same input
and `&&&` on `Kleisli` sequences left-to-right exactly as the `for` did (proved
in `KleisliLawSuite`, including the two discard shapes). The output is simply
busier, which is why it is behind a flag. Fixtures: `ArrowBodyAggressiveLift`
(fires, arity 3 with `ask`), `ArrowBodyAggressiveConstArm` (arity 2, one arm
ignores the input, no reshape), `ArrowBodyDiscardLeading` (`*>`, one named
generator), `ArrowBodyDiscardTrailing` (`&&&` then `flatTap`),
`ArrowBodyDiscardDeclined` (both refused shapes, no output file) and
`ArrowBodyPlainForDeclined` (identical body, no flag, declined).

## Cross-file callees

A callee declared in another file used to be invisible, and the failure was
silent: the body stayed wrapped and looked exactly like a body the rule had
correctly declined.

The cause is structural, not configuration. `SemanticDocument.info(symbol)`
answers from scalafix's symbol table, which resolves anything outside the
current file out of *classfiles* — and a Scala 3 classfile carries its signature
as TASTy, which the classfile-to-SemanticDB converter cannot decode. So `info`
came back empty, `KleisliType` read that as "not a Kleisli", and every
composition that crossed a file boundary was declined. Passing scalafix
`--classpath` does not help, in any combination of classes directory and
semanticdb targetroot.

`fix.arrow.KleisliScope` closes it by reading the compiler's own `.semanticdb`
payloads — which *do* carry full Scala 3 signatures — and folding them into a
project-wide map of `symbol -> (returns a Kleisli?, explicit parameter clauses)`.
`KleisliType` consults it only when `info` is empty, so a signature scalafix did
resolve stays authoritative. This is the same move `PreferKleisli` already makes
for its cross-file lift scope (`fix.KleisliLiftScope`).

The payload roots arrive via `Configuration.scalacClasspath`, which the CLI
populates from `--semanticdb-targetroots`; no extra configuration key exists and
none is needed. Aliases are followed through the payload's own type
representation, with the same alias-versus-abstract-type discriminator
`KleisliType` uses — an alias records its right-hand side as both bounds — so the
corpus's `ProgramArrows[-->[_, _]]` type parameter is still not confused with the
`-->` Kleisli alias that shares its spelling.

Fixtures: `crossfile/ArrowCrossFileStore.scala` declares the callee and is left
untouched; `crossfile/ArrowCrossFileCaller.scala` holds a body byte-identical in
shape to one in `ArrowBodyLocalProjection.scala`, and is what proves the two now
rewrite alike.

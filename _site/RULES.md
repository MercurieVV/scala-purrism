# Engineering Rules

## Scalafix Rules

- Implement rules as Scalafix semantic rules under `scalafix/src/fix`.
- Prefer semantic information over syntax-only matching when a rewrite depends on symbols or inferred types. Identifier names are not identity: two unrelated fields can share one.
- Rules must be deterministic, idempotent, and safe to run repeatedly.
- Every automatic rewrite must have an executed fixture under `scalafix/testInput` and `scalafix/testOutput`. See [Golden Fixtures](GOLDEN_FIXTURES.md).
- `PreferCatsFunctions` normalization/preservation/ranking/decline semantics are specified in [Prefer Cats Functions](PREFER_CATS_FUNCTIONS.md); conform to that contract rather than re-deriving equivalence rules ad hoc.
- Checks that cannot be safely rewritten should report diagnostics instead of producing partial edits.
- Report at the granularity of the decision, not of the evidence. A method that calls `out.write` twenty times poses one question — should it be `F[Unit]` — so it gets one diagnostic, anchored on the signature that would change. Twenty diagnostics bury the question under its evidence.
- A rewrite must preserve the expression's type. `try e catch { case _: Throwable => () }` has type `Unit`; `Either.catchNonFatal(e).void` has type `Either[Throwable, Unit]`, so the tidier form is a different program. Narrow the pattern instead. Where the type-preserving form needs a fact the expression does not carry — that a body is already in `F`, that every use of a reference is — report it.
- Rewriting rules should honour the `// purrism:keep <reason>` marker (`fix.Suppression`). Realtime and measured paths are deliberately un-idiomatic, and no amount of semantic information says so; only the author does.
- Prefer splicing a replacement into the original source text over rendering a transformed tree. Scalameta re-prints a transformed tree from its structure, so a substitution inside a string interpolation comes back reflowed across several lines.
- Anchor every `Patch` on a node from `doc.tree`. A tree parsed from any other `Input` — including a re-parse of `doc.input.text` — carries positions that only coincide by luck, and writing at those offsets corrupts the file.
- Emit diagnostics as `LintSeverity.Warning` unless they should genuinely block. Scalafix withholds a rule's patches when it reports lint *errors*, so an over-severe diagnostic silently turns the rewrite into a no-op.
- A rewrite that changes a *signature* must re-shape every call site, and scalafix can only patch the document it is handed. So either restrict the rewrite to definitions whose callers are provably in that file, or decide once for the whole project — never per file. A filter applied per file can decline a definition after another file has already re-split its calls, and the project stops compiling. `KleisliLiftScope` is the project-wide form: it reads the SemanticDB payload up front and every document then acts on that shared verdict, applying no judgement of its own.
- The idiom rules are individually idempotent but not confluent in a single pass, so run them to a fixpoint. One rule's output is another's input: `PreferOptionIdioms` turns `opt.map(f).getOrElse(F.unit)` into a `fold`, and that `fold` is what `PreferEffectIdioms` recognises as `traverse_`. Neither sees the other's work within a pass, because both match the text scalafix handed them. `IdiomCrossRule` and `IdiomCrossRuleSecondPass` pin both halves.
- A rule that consumes another rule's output needs a recompile between them. SemanticDB describes the code as it was compiled; after a signature rewrite the payload is stale, and a rule reading types from it is reasoning about code that no longer exists. `PreferKleisli` → recompile → `PreferArrow` is the pipeline, not one invocation listing both.
- `PreferCatsFunctions` normalization/preservation/ranking/decline semantics are specified in [Prefer Cats Functions](PREFER_CATS_FUNCTIONS.md); conform to that contract rather than re-deriving equivalence rules ad hoc.
- Since 0.9.0 the three signature-widening rules are `PreferPolymorphicTypeclasses`, `PreferPolymorphicCollections`, and `PreferPolymorphicCollectionOps` (formerly `PreferHKTTypeclasses`, `PreferContainerTypeclasses`, and `PreferElementTypeclasses`). The old names, old config case classes, and old `rules = [...]` entries stay resolvable as deprecated forwarders (`scalafix/src/fix/DeprecatedPolymorphicAliases.scala`) for one deprecation cycle before removal.

## Change Closures

A rewrite that changes a type has to follow that type wherever the value goes.
`fix.flow` holds the shared half of that — `Node`, `Edge`, `Facts`,
`Reachability` — and says nothing about what following the value *means*.

The meaning is not shared, and the difference is not a detail:

- **Opaque propagation is monomorphic.** The type genuinely spreads. Every
  signature the value reaches must change too, and where it meets something
  outside the closure the value can still cross by being wrapped or unwrapped.
  That is what `fix.opaque.Closure` computes: `genesis`, `leaves`, `mergePoints`,
  and `widen` to pull an intruder in.
- **Container widening is polymorphic.** `def f(xs: List[A])` becoming
  `def f[S[_]: Foldable](xs: S[A])` generalises at *one* site; every call site
  then re-instantiates `S`, and inference does that silently. So the value
  flowing out of the definition into a concrete `Seq`-typed signature is
  harmless — `f(myList)` still infers `S = List`. Only an escape *inside* the
  widened body is fatal, because there `S` is universally quantified and cannot
  be instantiated: `Phrase(pitches.map(f), d)` is the failing shape.

Seating `PreferPolymorphicCollections` on the opaque closure was tried and
reverted: forward reachability leaves the definition through its own return,
which the closure calls an escape and inference calls ordinary. `ContainerFlow`
stays syntactic and in-body for that reason, not for want of a graph.

## Widening and its call sites

`PreferPolymorphicCollectionOps`, `PreferPolymorphicCollections` and
`PreferPolymorphicTypeclasses` each name what they abstract — the element, the
collection, any other unary constructor — and `PreferTypeParameters` runs all
three, named for the mechanism they share. Keep new members named after their
subject: the umbrella is the only place the mechanism is the identity.


Adding a type parameter is not the source-compatible half of a widening. `f(xs)`
re-infers, but `f[A](xs)` names one type argument and a def that has grown a
second no longer accepts it — and the call usually lives in another file, often
another module, so no per-file decision can see it. `fix.container.WidenScope`
is the project-wide answer, built the way `KleisliLiftScope` is: it reads the
SemanticDB signatures for the type parameters and the parameter types, and the
parsed sources for the one question the payload does not record — was this call
*written* with type arguments?

**Propagate first, protect last.** Three verdicts, and every file in the run
computes the same one:

- **repairable** — widen, and *drop* the type-argument list at each call site.
  Open only when every type parameter of that def occurs in a value parameter,
  so inference recovers all of them, the new one included.
- **appendable** — widen, and *tell* each call site the new argument:
  `describe[Int](rows)` becomes `describe[Int, List](rows)`, reading `List` off
  the argument the call already passes. This is what keeps a def whose type
  parameter arrives through evidence (`[A: Show]`) from being refused: the
  answer is available, so it is supplied rather than inferred.
- **vetoed** — only when neither repair reaches every call site, because some
  argument names no container this can read: `summarise[Int](if (flag) List(1, 2)
  else Nil)`. Report, widen nothing, patch nothing. A call site left behind is a
  build that does not compile, so one unreachable site vetoes the whole
  widening.

Both repairs read the argument from the payload rather than its spelling: a
`val` through the type of the symbol it refers to, a `List(1, 2)` through the
companion it applies.

**What gets widened is configuration, and the default is a list.** Each rule's
`containers` names the constructors it claims, and `PreferPolymorphicCollectionOps`
additionally names the concrete element types it will assume an instance for.
`fix.ContainerNames` gives that list one wildcard, `"*"`, meaning every
candidate: every constructor the Cats index has a theory of. `WidenScope` has no
such index, so the rule hands it that test as a predicate rather than letting it
take a unary application at face value — **a scope that predicts more widenings
than the rules perform rewrites call sites of definitions nothing changed.** A
first wildcard run over a real project appended a type argument to
`Measurements.functions[F, FiniteDuration](pr)` — a def no rule widened, whose
`PrometheusRegistry[F]` parameter only *looked* like a container — and stopped
that module compiling. The wildcard is also restricted to names a type argument
can be written with: the last segment of a symbol is not always a type, and
`IndexedSeq.empty` and a type lambda's parameter arrived as `Delegate#empty()`
and `[F]`, both duly appended. And the scope descends into a parameter type's
arguments only through an *abstract* head, mirroring
`UsageAnalyzer.outerConcreteTargets`: in `Ref[F, Option[Mixed]]` the target is
`Ref`, declined for being binary, and no rule ever widens the `Option` inside
it — descending anyway made every such def a candidate and stripped the type
arguments off its call sites for nothing. The wildcard is a value rather than the empty list because empty already
means "widen nothing" here and "cede nothing" in `PreferPolymorphicTypeclasses`, and
inverting it would change what existing configurations do. The two lists are one
setting in practice: a wildcard on the container rule wants the same wildcard on
the HKT rule's cede-list, or both widen one signature.

Evidence is not an argument. `def recording[F[_]: Sync](dir: Path)` desugars to
a `(using Sync[F])` parameter whose type mentions `F`, but nothing in the
argument list does, so `recording[IO](dir)` cannot simply drop its type
argument and expect inference to find `F` again. Implicit and given parameters
are therefore excluded when deciding whether a type parameter is inferable --
without that exclusion a whole-project run rewrites call sites of definitions it
never widened, which is how this was found.

Two things this deliberately does not do. It does not re-derive the verdict from
the definition after the fact ("declares more type parameters than the call
passes"): SemanticDB lists an extension method's own type parameters together
with its extension's, and that test rewrites `xs.at[Store[V]](id)` for nothing.
And it does not survive the two halves landing in different runs — see the
ordering rule in `README.md`: one invocation over the whole project, or module
by module with dependents first.

## Candidate Rules

Shapes surveyed and specified, not yet implemented:

- `PreferNonEmpty` — `require(xs.nonEmpty)` on a collection parameter is `NonEmptyList`/`NonEmptyVector`, and a `f(xs: A*)` that reduces its argument is `f(head: A, tail: A*)`. Deletes the runtime check rather than validating it. Signature-changing, so it needs the project-wide call-site verdict.
- Summon-style bodies. `UsageAnalyzer` attributes a capability to the *receiver* of a call, so `Reducible[NonEmptyList].reduce(xs)` states nothing about `xs` — the container is only an argument — and `PreferPolymorphicTypeclasses` leaves it alone. Reading it would also mean rewriting `Reducible[NonEmptyList]` to `Reducible[G]` in the body, which no rule in `fix.hkt` does today.
- Result-position containers. `def square[T](count: Int, from: Int): Seq[(T, T)] = Range(...).map(...)` names a container the caller never supplies, so `PreferPolymorphicCollections.isParameter` refuses it: widening the signature alone gives `square[S[_]]: S[(T, T)]` over a body that still returns a `Seq`, which is not a program. Reaching `S` here means *building* it — `Range(...).foldMap(k => (...).pure[S])` under `Applicative` and `MonoidK` — and that is a body rewrite, the same wall as the entry below. Cats has no `Range ~> S`.
- Lifting a concrete constructor into `F`. `private def parse(s: String): Try[Int] = Try(s.toInt)` is a `MonadError` in disguise, but reaching `parse[F[_]: MonadError[*, Throwable]]` means rewriting the body to `F.fromTry(Try(...))`. The engine widens signatures; a body rewrite is a second rule.
- Element-level evidence. Widening `xs: List[B]` to `G[B]` under `Foldable` says nothing about the `Monoid[B]` that `xs.foldMap(identity)` also needs. Today the compiler is the check: if the evidence is absent the widened file does not compile. `DeclineReason.MissingEvidence` exists for the rule that would decline instead.

## Typelevel Style

- Prefer abstract effect APIs such as `F[_]: MonadThrow`, `Sync`, `Temporal`, or `Concurrent` over concrete `IO` in library code.
- Keep concrete `IO` at application boundaries such as `IOApp`.
- Use `cats.syntax.all.*` for standard Cats syntax.
- Avoid `null`, `throw`, `return`, mutable `var`, unsafe casts, and unsafe effect execution.
- Model errors explicitly with effect errors, `Either`, validated data, or domain ADTs.

## Testing

- Executed fixtures are mandatory for refactoring behavior.
- Keep one matching relative path in `testInput/src` and `testOutput/src` for each scenario.
- Add focused tests for helper logic when a rewrite algorithm becomes non-trivial. Keep the analysis behind a plain interface (as `Closure` sits behind `Facts`) so it can be driven by a fake, with no compiler in the loop.
- Prefer small fixtures that isolate one transformation at a time.
- Assert a rewriter's expected output from a file under `scalafix/testOutput/src`, read with `scalafix.testkit.ExpectedSources`, not from a string in the test source. A string is never compiled, so such a test asserts that the renderer is *stable* rather than *correct*: `(using G: Functor)` -- which names a type that does not exist -- sat in `HktRewriterSuite`'s expectations until a real codebase hit it, because every executed fixture goes through `testOutput.compile` and these did not.
- Never guard a test with `assume` on a path resolved relative to the working directory. The forked test JVM has a different one, so the test skips and reports green forever. Locate build outputs through generated properties and `require` that they exist.
- Run mutation testing with Stryker4s for behavior-heavy logic when the rule implementation matures.

## Maintenance

- Keep dependencies current with Scala Steward.
- Run Scala Steward against the hosted repository slug once a git remote exists.
- Keep generated IDE/build output out of version control.
- Keep agent-specific instructions as pointers to these shared docs.

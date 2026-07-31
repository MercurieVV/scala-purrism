# PreferCatsFunctions — Normalization Contract & Fixture Oracle

Single source of truth for `PreferCatsFunctions` semantics. Every later
implementation phase (Cats source index, project candidate extraction,
equivalence engine, renderer/ranking, fixtures) must conform to this doc
without further judgment calls. If code and this doc disagree, this doc wins
and the code is the bug — file an issue against this doc if the contract
itself needs to change.

The rule's contract, restated from #34:

```
abstract(originalFunctionBody) == abstract(catsFunctionBody)
```

`abstract` is the normalizer defined in §1–2. Equality is IR equality after
normalization, not tree shape and not name/text resemblance.

## 1. Erasure list

Differences the normalizer MUST erase (i.e. two bodies differing only in
these ways are still considered equal):

| # | Erased difference | Justification |
|---|---|---|
| E1 | Local/param names, via alpha-renaming keyed on SemanticDB local-symbol identity (not textual name) | Two locals with the same name in unrelated scopes are not the same binding, and two differently-named locals with the same de Bruijn-style position are; only symbol identity tells them apart, text does not. |
| E2 | `for` comprehension vs explicit `flatMap`/`map` desugaring | The compiler desugars `for` to the same `flatMap`/`map`/`withFilter` calls; the two are one program, not two. |
| E3 | Eta-expansion vs direct application (`f` vs `x => f(x)` at matching arity/type) | Eta-expansion is a syntactic wrapper the compiler inserts/removes; it never changes what runs. |
| E4 | Explicit vs inferred type arguments, only when SemanticDB proves the inferred argument equals the explicit one | Once the compiler's own inference is consulted, the explicit annotation is redundant surface syntax, not a semantic choice. |
| E5 | Import spelling and qualification (`cats.syntax.all.*` vs `cats.syntax.functor.*` vs fully-qualified call) | Same resolved symbol via SemanticDB regardless of how the import was written or whether the call is qualified. |
| E6 | Placeholder literal identity (e.g. which literal constant stands in for "any value of this type" in a generalized body) | The normalizer must treat concrete sample literals in Cats source (e.g. `Nil`, `0`) as position placeholders when the surrounding code is generic over the value, otherwise no user code would ever match. |

Only E1–E6 are erasure axes. Anything not listed here is preserved by
default (§2 is illustrative of the highest-risk cases, not exhaustive).

## 2. Preservation list

Differences the normalizer MUST preserve. If any of these differ between
the user body and the Cats body, the two are NOT equal — no patch, and no
inference "papers over" the difference:

| # | Preserved difference | Justification |
|---|---|---|
| P1 | Evaluation order | Reordering can change observable effects (`F` with side effects, `Eval`, mutable receivers). |
| P2 | Evaluation count (once vs N times) | A body that evaluates an effectful subexpression twice is not interchangeable with one that evaluates it once, even if both "return the same value" abstractly. |
| P3 | By-name vs strict parameter passing | By-name changes when/if/how-often the argument expression runs; substituting strict for by-name (or vice versa) is a behavior change, not a syntax change. |
| P4 | `throw` / `return` | Both are non-local control transfer; erasing them would silently change exception/control-flow semantics, which is exactly what the non-goals in #34 forbid. |
| P5 | Mutation / local `var` | Presence of mutable state is an effect the normalizer cannot abstract away without changing what the program does under aliasing/reentry. |
| P6 | Short-circuiting behavior | `&&`/`||`/`orElse`-style short-circuit changes which subexpressions run at all; equating it with a non-short-circuiting form changes P1/P2 too. |
| P7 | Left vs right side of bifunctor-like values (`Either`, `Ior`, validated types) | Swapping sides silently flips success/failure semantics even when the shapes look symmetric. |
| P8 | Required typeclass constraints | A Cats function requiring a weaker or different constraint than the user's context provides is not a like-for-like replacement; the rule must not introduce new constraint requirements unsafely (ties into decline rule D4). |
| P9 | Collection ordering / strictness contracts | A Cats function that does not promise the same ordering or evaluation strictness as the concrete collection in user code cannot be substituted losslessly. |

Erasure (§1) and preservation (§2) are exhaustive and mutually exclusive for
axes considered in this contract: an axis not in §1 defaults to preserved.

## 3. Decline rules

Each condition below produces **at most one `LintSeverity.Warning`** and
**zero patches**. Decline rules never partially rewrite.

| # | Condition | Behavior |
|---|---|---|
| D1 | Ambiguous ranking — §4's total order does not resolve to a unique winner | Warn once, no patch. |
| D2 | The only normalized match is a private/internal-only Cats implementation detail, with no public API of identical normalized body | Warn once, no patch. Never rewrite to a non-public symbol. |
| D3 | Missing typeclass evidence — the matched Cats function requires a constraint (§2 P8) not derivable in the enclosing scope | Warn once, no patch. |
| D4 | The rewrite would require strengthening the enclosing signature's constraints in a way that cannot be proven safe project-wide (see `KleisliLiftScope` precedent in `docs/RULES.md`: signature changes are a whole-project decision, never a per-file guess) | Warn once, no patch. |

## 4. Ranking rule (deterministic total order)

When normalized user IR matches more than one Cats candidate body, rank
candidates using this ordered comparator, applied lexicographically until
one candidate strictly precedes all others:

1. **Public syntax/typeclass method over internal helper.** Public API is
   the whole point of the rewrite (#34 non-goals forbid rewriting to
   private internals).
2. **Already in scope (imported/resolvable without a new import) over
   requiring a new import.** Minimizes blast radius of the patch.
3. **Shorter rendered call form, only as the final tiebreak** (strictly
   fewer rendered tokens in the call-site replacement). This is the *last*
   criterion, never the first, per #34's ranking spec.
4. **Otherwise: decline** (rule D1). If two candidates remain tied after
   criteria 1–3, ranking is ambiguous by definition — there is no further
   tiebreak, and inventing one (e.g. alphabetical order) would produce
   nondeterministic-feeling output as the Cats index grows. Decline instead.

This is a total order up to the point where criteria 1–3 exhaust
themselves; beyond that point the rule is defined to decline, so the
overall function (candidates → outcome) is still deterministic — it simply
maps remaining ties to "no patch" rather than picking one arbitrarily.

## 5. Fixture oracle

Fixture names are authoritative and come from #34. Each fixture below
states: input intent, expected output, and which rule (§1–4) it pins.
Downstream phases implement exactly these fixtures — do not rename, add, or
drop fixtures without updating this doc first.

### Positive fixtures (patch expected) — 8

| Fixture | Input intent | Expected output | Pins |
|---|---|---|---|
| `CatsEquivalentMapIdentity` | Hand-written code that maps a functor with an identity-shaped function (e.g. `fa.map(a => a)`) | Rewritten to drop the redundant map / use the Cats identity-preserving form (e.g. bare `fa`, or `Functor[F].map(fa)(identity)` normalized away) | E1 (param name `a` erased), ranking §4 crit. 1 (public syntax over internal) |
| `CatsEquivalentVoid` | Hand-written `fa.map(_ => ())` or equivalent discard-result pattern | Rewritten to `fa.void` (`cats.syntax.functor.void`) | E1, E3 (eta/direct application equivalence for the discarded function), §4 crit. 3 (shorter, final tiebreak only) |
| `CatsEquivalentAs` | Hand-written `fa.map(_ => b)` for a fixed value `b` | Rewritten to `fa.as(b)` | E1, E6 (placeholder erasure for `b`) |
| `CatsEquivalentProduct` | Hand-written pair construction via nested `flatMap`/`map` producing a tuple of two independent effects | Rewritten to `fa.product(fb)` or `(fa, fb).tupled` per ranking | E2 (for/flatMap desugaring erased), P1 (must confirm evaluation order of `fa` then `fb` is preserved by the chosen replacement — if order can't be proven preserved, this fixture must not match) |
| `CatsEquivalentFoldMap` | Hand-written fold accumulating via a `Monoid`-shaped combine over a mapped collection | Rewritten to `xs.foldMap(f)` | E1, E2, P8 (requires `Monoid` evidence already derivable — this fixture assumes it is; see `CatsEquivalentMissingTypeclassEvidence` for the negative counterpart) |
| `CatsEquivalentTraverse` | Hand-written manual accumulation of independent `F[_]` effects across a collection into `F[List[B]]` | Rewritten to `xs.traverse(f)` | E1, E2, P1/P2 (traverse's evaluation order/count must match the hand-written loop exactly, else decline) |
| `CatsEquivalentForComprehension` | A `for`-comprehension whose desugared body matches a Cats combinator's normalized body | Rewritten to the matched combinator call | E2 (this is the fixture that specifically pins for/flatMap desugaring equivalence) |
| `CatsEquivalentLocalHelperMethod` | A locally-defined helper `def` whose body normalizes identically to a public Cats function's body | Rewritten to call the Cats function directly (helper call site rewritten; unused helper def left for a separate dead-code concern, out of scope here) | E1, ranking §4 crit. 1 (public Cats method preferred over the user's own private helper, which is the internal-helper case from the *user's* side) |

### Negative / safety fixtures (no patch) — 9

| Fixture | Input intent | Expected behavior | Pins |
|---|---|---|---|
| `CatsEquivalentAlmostSameButOrderDiffers` | Body looks like a Cats match except two subexpressions are evaluated in swapped order vs the Cats reference | Unchanged, no patch. Emits `LintSeverity.Warning` only if the near-miss is confidently detected (else silent) | P1 |
| `CatsEquivalentAlmostSameButEvaluatesTwice` | Body looks like a Cats match except one subexpression is evaluated twice where the Cats reference evaluates it once (or vice versa) | Unchanged, no patch; warning if confidently detected | P2 |
| `CatsEquivalentAlmostSameButByNameDiffers` | Body looks like a Cats match except a by-name parameter in one side is strict in the other | Unchanged, no patch; warning if confidently detected | P3 |
| `CatsEquivalentUsesMutation` | Body uses a local `var` or mutable state where the Cats reference is pure | Unchanged, no patch; warning if confidently detected | P5 |
| `CatsEquivalentThrows` | Body contains `throw` (or relies on exception control flow) where the Cats reference models the error functionally | Unchanged, no patch; warning if confidently detected | P4 |
| `CatsEquivalentAmbiguousCatsMatches` | Normalized body matches ≥2 Cats candidates that remain tied after all of §4's ranking criteria | Unchanged, no patch, exactly one `LintSeverity.Warning` | D1, §4 (documents the "decline" branch of the ranking function) |
| `CatsEquivalentPrivateCatsImplOnly` | Normalized body matches only a private/internal Cats implementation detail, no public API with the same normalized body | Unchanged, no patch, one `LintSeverity.Warning` | D2 |
| `CatsEquivalentMissingTypeclassEvidence` | Normalized body matches a Cats function requiring a constraint (e.g. `Monoid`) not derivable in the enclosing scope | Unchanged, no patch, one `LintSeverity.Warning` | D3, P8 |
| `CatsEquivalentRequiresUnsafeSignatureChange` | Match only holds if the enclosing method's signature is strengthened with a new/stronger constraint that cannot be safely propagated project-wide | Unchanged, no patch, one `LintSeverity.Warning` | D4 |

Note on "warning if confidently detected" (the five near-miss fixtures):
these fixtures pin a *preservation* axis, not a detection guarantee. If the
equivalence engine's confidence in the near-match is below its own
threshold, "unchanged, no diagnostic" is also an acceptable outcome for
that specific fixture — but "patch produced" is never acceptable. The
fixture asserts the negative (no patch), and a warning is bonus signal, not
a hard requirement, since forcing a warning on every near-miss the engine
happens to notice would tie the contract to implementation-specific
detection thresholds rather than to the safety property that matters (no
false-positive rewrite).

## Spec summary (for issue #97)

This section is copied verbatim into the GitHub issue body under
"## Spec summary" per the acceptance criteria.

- Erasure axes (§1): local/param alpha-renaming by SemanticDB symbol
  identity, for/flatMap desugaring, eta-expansion, explicit-vs-inferred
  type args (only when SemanticDB proves equality), import
  spelling/qualification, placeholder-literal identity.
- Preservation axes (§2): evaluation order, evaluation count, by-name vs
  strict, throw/return, mutation/var, short-circuiting, bifunctor
  left/right side, required typeclass constraints, collection
  ordering/strictness.
- Decline rules (§3): ambiguous ranking, private-only Cats match, missing
  typeclass evidence, unsafe signature strengthening — each yields ≤1
  `LintSeverity.Warning` and zero patches.
- Ranking (§4): public API > internal helper; in-scope > new import;
  shorter only as final tiebreak; unresolved ties decline (deterministic
  by construction — ties always map to "no patch").
- Fixture oracle (§5): all 8 positive and 9 negative fixture names from #34
  are enumerated with input intent, expected outcome, and the erasure/
  preservation/decline/ranking rule each one pins.

## Corpus run

The corpus run was attempted on 2026-07-27, but could not execute: this checkout
does not contain a `PreferCatsFunctions` rule (no implementation or Scalafix
service registration), so there is no runnable rule to apply and no proposed
rewrite to audit. The local rule build was also blocked before compilation by
Coursier's cache lock permission error at
`/Users/viktorskalinins/Library/Caches/Coursier/v1/.structure.lock`.

All four repositories were attempted and skipped for the same reason:

| Repository | Proposed rewrites | Declines | Audit |
| --- | ---: | --- | --- |
| `gh-tasks-llm-executor` | n/a | n/a — rule unavailable | no proposed rewrites |
| `arrowstep` | n/a | n/a — rule unavailable | no proposed rewrites |
| `cctv-analyzer-scala` | n/a | n/a — rule unavailable | no proposed rewrites |
| `ScalaSemanticMCP` | n/a | n/a — rule unavailable | no proposed rewrites |

No false-positive follow-up can be filed from this run because no rewrite was
proposed. Once the rule is available, rerun this audit and file any false
positive as a follow-up to #34.

No source files were changed in the corpus repositories. They contained
pre-existing working-tree changes, which were left untouched.

ScalaSemantic MCP was unavailable for this audit: the required
`set_workspace_root` call was rejected as `user cancelled MCP tool call`.

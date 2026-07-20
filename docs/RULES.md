# Engineering Rules

## Scalafix Rules

- Implement rules as Scalafix semantic rules under `scalafix/src/fix`.
- Prefer semantic information over syntax-only matching when a rewrite depends on symbols or inferred types. Identifier names are not identity: two unrelated fields can share one.
- Rules must be deterministic, idempotent, and safe to run repeatedly.
- Every automatic rewrite must have an executed fixture under `scalafix/testInput` and `scalafix/testOutput`. See [Golden Fixtures](GOLDEN_FIXTURES.md).
- Checks that cannot be safely rewritten should report diagnostics instead of producing partial edits.
- Anchor every `Patch` on a node from `doc.tree`. A tree parsed from any other `Input` — including a re-parse of `doc.input.text` — carries positions that only coincide by luck, and writing at those offsets corrupts the file.
- Emit diagnostics as `LintSeverity.Warning` unless they should genuinely block. Scalafix withholds a rule's patches when it reports lint *errors*, so an over-severe diagnostic silently turns the rewrite into a no-op.

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
- Never guard a test with `assume` on a path resolved relative to the working directory. The forked test JVM has a different one, so the test skips and reports green forever. Locate build outputs through generated properties and `require` that they exist.
- Run mutation testing with Stryker4s for behavior-heavy logic when the rule implementation matures.

## Maintenance

- Keep dependencies current with Scala Steward.
- Run Scala Steward against the hosted repository slug once a git remote exists.
- Keep generated IDE/build output out of version control.
- Keep agent-specific instructions as pointers to these shared docs.

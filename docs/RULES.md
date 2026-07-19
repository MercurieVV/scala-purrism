# Engineering Rules

## Scalafix Rules

- Implement rules as Scalafix semantic rules under `scalafix/src/fix`.
- Prefer semantic information over syntax-only matching when a rewrite depends on symbols or inferred types.
- Rules must be deterministic, idempotent, and safe to run repeatedly.
- Every automatic rewrite must have a golden fixture in `scalafix/test/resources/golden`.
- Checks that cannot be safely rewritten should report diagnostics instead of producing partial edits.

## Typelevel Style

- Prefer abstract effect APIs such as `F[_]: MonadThrow`, `Sync`, `Temporal`, or `Concurrent` over concrete `IO` in library code.
- Keep concrete `IO` at application boundaries such as `IOApp`.
- Use `cats.syntax.all.*` for standard Cats syntax.
- Avoid `null`, `throw`, `return`, mutable `var`, unsafe casts, and unsafe effect execution.
- Model errors explicitly with effect errors, `Either`, validated data, or domain ADTs.

## Testing

- Golden fixtures are mandatory for refactoring behavior.
- Keep one matching file name in `base` and `expected` for each scenario.
- Add focused tests for helper logic when a rewrite algorithm becomes non-trivial.
- Prefer small fixtures that isolate one transformation at a time.
- Run mutation testing with Stryker4s for behavior-heavy logic when the rule implementation matures.

## Maintenance

- Keep dependencies current with Scala Steward.
- Run Scala Steward against the hosted repository slug once a git remote exists.
- Keep generated IDE/build output out of version control.
- Keep agent-specific instructions as pointers to these shared docs.

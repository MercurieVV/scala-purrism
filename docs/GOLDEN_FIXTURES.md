# Golden Fixtures

Golden fixtures are the contract for this project.

For each scenario, add one Scala source file under:

```text
app/test/resources/golden/typelevel/base
```

and a same-named expected file under:

```text
app/test/resources/golden/typelevel/expected
```

The `base` file is the code before Scalafix runs. The `expected` file is the exact code after all project rules have been applied.

Rules:

- Use identical file names in `base` and `expected`.
- Keep each fixture focused on one refactoring behavior.
- Prefer realistic Cats and Cats Effect examples over synthetic syntax puzzles.
- Add comments only when the fixture would otherwise be ambiguous.
- Treat expected files as authoritative. If implementation behavior and expected files differ, fix the implementation or intentionally update the expected file with a clear reason.

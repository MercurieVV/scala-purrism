# Golden Fixtures

Golden fixtures are the contract for this project. There are two kinds, and they
are not interchangeable.

## Executed fixtures (`scalafix/testInput` and `scalafix/testOutput`)

These are the ones a test actually runs. `SemanticFixtureSuite` compiles every
file under `scalafix/testInput/src` with SemanticDB, runs the rules named in the
file's own header comment, and diffs the result against the same-named file
under `scalafix/testOutput/src`.

Each input file opens with a testkit comment naming its rules and any config:

```scala
/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  { name = "TicketId", underlying = "scala/Predef.String#", seeds = [ "golden/Ticket#id." ] }
]
 */
package golden
```

Rules:

- Use identical relative paths under `testInput/src` and `testOutput/src`.
- **Both** modules must compile. An expected output that does not typecheck
  fails the build, which is deliberate — it is what proves a rewrite produces
  valid code.
- If a rule changes nothing, no output file is needed.
- Keep each fixture focused on one behaviour.
- Prefer realistic Cats and Cats Effect examples over synthetic syntax puzzles.
- Set `SCALAFIX_SAVE_EXPECT=true` to overwrite expected files with current
  output. Read the diff before committing it.

A fixture that exists only to give the analysis something to inspect — rather
than to pin a rewrite — should name a rule that changes nothing, so it needs no
output file. `golden/KleisliFlow.scala` does this with `rules = [DisableSyntax]`.

## Illustrative fixtures (`scalafix/test/resources/golden/typelevel`)

The older `base`/`expected` pairs under `scalafix/test/resources`. **No test
runs a rule against these.** `GoldenFixtureSuite` only checks that the two
directory listings match; the files document intent for `OpaqueTypePropagation`
and the Typelevel rules but nothing enforces them. Several already disagree with
what their rule actually produces.

Do not add fixtures here — an unexecuted fixture reports green no matter what
the rule does. New work belongs in `testInput`/`testOutput`.

## Why the split exists

`scalafix-testkit`, which supplies the machinery for executed fixtures, is
ScalaTest-based while this project runs munit, and the entry points needed to
construct a `SemanticDocument` are `private[scalafix]`. `MunitSemanticRuleSuite`
lives in package `scalafix.testkit` for exactly that reason; see its scaladoc.

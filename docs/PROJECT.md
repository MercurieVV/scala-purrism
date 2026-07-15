# Project

`scalafix-purrism` is a Scalafix semantic-rule project for automatically refactoring and checking Typelevel Scala code, with a focus on Cats and Cats Effect.

The rule set should move code toward pure, polymorphic Typelevel style. Typical targets include replacing unnecessary concrete `IO` APIs with abstract `F[_]` APIs, introducing Cats typeclass constraints, and preserving behavior while making effects explicit and testable.

The source of truth is the golden fixture set:

- `app/test/resources/golden/typelevel/base`: original input classes.
- `app/test/resources/golden/typelevel/expected`: expected classes after refactoring.

Every rule change must be justified by adding or updating a matching base/expected golden pair. The implementation must follow the fixtures, not separate prose-only expectations.

## Build

- Build tool: Mill.
- Rule implementation Scala version: Scala 3.8.4.
- Scalafix rule/testkit artifacts use full Scala 3 version suffixes, such as `scalafix-rules_3.8.4`.
- Target code style: Scala 3 Typelevel code is expected in fixtures.
- Tests: MUnit.
- Docs: mdoc.
- Maintenance: Scala Steward.

Useful commands:

```bash
rtk mill app.compile
rtk mill app.test
rtk mill docs.compile
```
